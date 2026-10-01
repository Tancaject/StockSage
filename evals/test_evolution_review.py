"""Fictional blind opinions exercise disputes and release gates without model calls."""
import copy
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from evolution_compare import blind_material, compare_reports
from evolution_review import adjudication_template, finalize_reviews, verify_review_audit, main
from evolution_rubric import DIMENSIONS
from eval_common import json_hash
from test_evolution_compare import experiment
from test_evolution_acceptance import KEY, comparison, reviewed_comparison


class IndependentReviewTest(unittest.TestCase):
    def setUp(self):
        self.manifest, self.baseline, self.candidate = experiment(group_count=1, repeats=1)
        packet, self.mapping = blind_material(self.manifest, self.baseline, self.candidate)
        self.packets = []
        for reviewer in ('reviewer-one', 'reviewer-two'):
            reviewed = copy.deepcopy(packet)
            for item in reviewed['items']:
                item.update(preference='TIE', reason='Both fictional options satisfy the same rubric.')
                for option in item['options']:
                    option.update(reviewer=reviewer, reviewed_at='2026-09-28T00:00:00Z',
                                  dimensions={name: {'status': 'PASS', 'reason': 'Fictional review'} for name in DIMENSIONS})
            self.packets.append(reviewed)

    def template(self):
        return adjudication_template(self.manifest, self.baseline, self.candidate, self.packets, self.mapping)

    def finalize(self, decisions):
        return finalize_reviews(self.manifest, self.baseline, self.candidate, self.packets, self.mapping, decisions, KEY)

    def test_agreement_keeps_ties_all_votes_and_exact_report_binding(self):
        final = self.finalize(self.template())
        audit = verify_review_audit(final['audit'], self.manifest, final['baseline'], final['candidate'], KEY)
        self.assertEqual(audit['counts'], {'trials': 2, 'disputes': 0, 'ties': 2, 'unreviewable': 0})
        self.assertTrue(all(len(row['votes']) == 2 for row in audit['trials']))
        changed = copy.deepcopy(final['candidate'])
        changed['cases'][0]['quality_review']['stages']['analysis']['dimensions']['claim_support']['status'] = 'FAIL'
        with self.assertRaisesRegex(ValueError, 'exact reviewed reports'):
            verify_review_audit(final['audit'], self.manifest, final['baseline'], changed, KEY)

    def test_disagreement_needs_distinct_adjudicator_and_retains_losing_opinion(self):
        self.packets[1]['items'][0].update(preference='B', reason='The second reviewer preferred the other answer.')
        decisions = self.template()
        self.assertEqual(decisions['trials'][0]['status'], 'DISPUTED')
        pending = self.finalize(decisions)
        self.assertEqual(pending['status'], 'PENDING_ADJUDICATION')
        self.assertNotIn('audit', pending)
        decision = decisions['trials'][0]['resolution']
        decision.update(reviewer='reviewer-one', reviewedAt='2026-09-28T01:00:00Z',
                        reason='Rechecked evidence and rubric; retain the tie.', selectedPacketSha256=json_hash(self.packets[0]))
        with self.assertRaisesRegex(ValueError, 'distinct adjudicator'):
            self.finalize(decisions)
        decision['reviewer'] = 'independent-adjudicator'
        final = self.finalize(decisions)
        row = final['audit']['payload']['trials'][0]
        self.assertEqual(row['finalPreference'], 'TIE')
        self.assertEqual([vote['preference'] for vote in row['votes']], ['TIE', 'B'])
        self.assertEqual(final['audit']['payload']['counts']['disputes'], 1)

    def test_score_disputes_and_unreviewable_trials_cannot_disappear(self):
        self.packets[1]['items'][0]['options'][0]['dimensions']['claim_support']['status'] = 'FAIL'
        self.assertEqual(self.template()['trials'][0]['status'], 'DISPUTED')
        altered = self.template()
        altered['trials'].pop()
        with self.assertRaisesRegex(ValueError, 'all declared'):
            self.finalize(altered)
        self.packets[1] = copy.deepcopy(self.packets[0])
        with self.assertRaisesRegex(ValueError, 'distinct independent reviewer'):
            self.template()
        self.packets = self.packets[:1]
        for item in self.packets[0]['items']:
            item['preference'] = 'UNREVIEWABLE'
            item['reason'] = 'Insufficient information to score this fictional result.'
        final = self.finalize(self.template())
        self.assertEqual(final['audit']['payload']['counts']['unreviewable'], 2)
        self.packets[0]['items'][0]['options'][0]['answer'] = 'Replacement answer'
        with self.assertRaisesRegex(ValueError, 'changed'):
            self.template()

    def test_missing_blind_audit_blocks_otherwise_accepted_real_contract(self):
        sources, gold, manifest, gate, baseline, candidate, resources = comparison()
        result = compare_reports(manifest, baseline, candidate, sources, gold, acceptance_gate=gate,
                                 resource_audit=resources, evaluator_key=KEY)
        self.assertEqual(result['status'], 'INCONCLUSIVE')
        self.assertIn('INDEPENDENT_BLIND_REVIEW_AUDIT_MISSING', result['reasons'])
        reviewed = reviewed_comparison(manifest, baseline, candidate, sources, gold, acceptance_gate=gate,
                                       resource_audit=resources, evaluator_key=KEY)
        self.assertEqual(reviewed['status'], 'IMPROVED')
        self.assertEqual(reviewed['review_summary']['trials'], 6)
        self.assertIsNotNone(reviewed['releaseEvidence']['reviewAuditSha256'])
        self.assertFalse(reviewed['releaseEvidence']['eligible'])

    def test_unexecuted_stage_remains_in_blind_material_and_audit(self):
        self.candidate['cases'][0]['replay']['finalAnswer'] = None
        self.candidate['cases'][0]['replay']['errorCode'] = 'MODEL_TIMEOUT'
        packet, self.mapping = blind_material(self.manifest, self.baseline, self.candidate)
        for item in packet['items']:
            item.update(preference='UNREVIEWABLE', reason='Fictional incomplete execution remains in review.')
            for option in item['options']:
                option.update(reviewer='independent-reviewer', reviewed_at='2026-09-28T00:00:00Z')
        self.packets = [packet]
        final = self.finalize(self.template())
        self.assertEqual(final['audit']['payload']['counts']['trials'], 2)
        self.assertEqual(final['audit']['payload']['counts']['unreviewable'], 2)
        self.assertIn('MODEL_TIMEOUT', json.dumps(packet))

    def test_cli_preserves_original_packets_and_emits_comparison_inputs(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            values = {'manifest': self.manifest, 'baseline': self.baseline, 'candidate': self.candidate,
                      'mapping': self.mapping, 'one': self.packets[0], 'two': self.packets[1]}
            for name, value in values.items():
                (root / (name + '.json')).write_text(json.dumps(value), encoding='utf-8')
            argv = ['review']
            for name in ('manifest', 'baseline', 'candidate', 'mapping'):
                argv.extend(['--' + name, str(root / (name + '.json'))])
            argv.extend(['--reviews', str(root / 'one.json'), str(root / 'two.json')])
            with patch('sys.argv', argv + ['--mode', 'template', '--output', str(root / 'template')]), redirect_stdout(io.StringIO()):
                self.assertEqual(main(), 0)
            with patch('sys.argv', argv + ['--mode', 'finalize', '--output', str(root / 'final'),
                                          '--adjudication', str(root / 'template/adjudication.json')]), \
                    patch.dict(os.environ, {'STOCKSAGE_EVOLUTION_EVALUATOR_KEY': KEY.decode()}), redirect_stdout(io.StringIO()):
                self.assertEqual(main(), 0)
            read = lambda name: json.loads((root / 'final' / name).read_text(encoding='utf-8'))
            verify_review_audit(read('review-audit.json'), self.manifest, read('baseline-reviewed.json'), read('candidate-reviewed.json'), KEY)
            for packet in self.packets:
                self.assertEqual(read('reviews/' + json_hash(packet) + '.json'), packet)


if __name__ == '__main__':
    unittest.main()
