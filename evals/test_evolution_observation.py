"""Fictional exports exercise observation/withdrawal, without model calls or deployment."""
import copy
import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

import evolution_observation as observation
import evolution_deployment as deployment
from eval_common import json_hash
import test_evolution_deployment
from test_evolution_release import PUBLISHER_KEY
from test_evolution_acceptance import KEY
from test_ordinary_answer_quality import sample_report, completed_reviews


class ObservationTest(unittest.TestCase):
    def setUp(self):
        self.helper = test_evolution_deployment.DeploymentTest()
        self.helper.setUp()
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        drill = self.helper.drill(self.root)
        accepted = deployment.accept_drill(self.helper.approved, drill, self.helper.rollback(drill), self.helper.proofs, KEY, PUBLISHER_KEY)
        self.activation = self.helper.serving(drill, accepted, self.root)

    def batch(self):
        report = sample_report()
        first = report['cases'][0]
        bundle = self.helper.package['bundle']
        identity = {'bundleId': bundle['bundleId'], 'bundleSha256': bundle['contentSha256']}
        method = {'kind': 'ordinary-evidence', 'route': 'FUNDAMENTALS', 'analystStatus': 'COMPLETED',
                  'methodBundle': identity, 'analystDurationMs': 50,
                  'analystInvocation': {'kind': 'model-invocation', 'scope': 'fundamentals-analysis',
                      'methodBundle': identity, 'actualSystemPromptSha256': json_hash('system'),
                      'actualUserPromptSha256': json_hash('task')},
                  'methodSelection': {'mode': 'SERVING', 'activationId': 'unit-serving', 'reason': 'APPROVED_SCOPE',
                      'authorizationSha256': self.activation['payloadSha256'], 'pinnedAt': self.helper.at(0),
                      'comparisonIdentity': self.helper.package['expectedComparisonIdentity']}}
        first['trace'].update(traceId='run-1', userId='internal-one', status='success', durationMs=100)
        first['trace']['steps'].append({'attributes': method})
        for count in (7, 9):
            first['trace']['steps'].append({'attributes': {'kind': 'model-usage', 'scope': 'final-answer',
                'usageSource': 'PROVIDER', 'inputTokens': count, 'outputTokens': 2}})
        second = copy.deepcopy(first)
        second.update(id='cancelled', passed=False)
        second['case_definition']['id'] = second['id']
        second['case_sha256'] = json_hash(second['case_definition'])
        second['trace'].update(traceId='run-2', status='cancelled', durationMs=1000)
        second['trace']['steps'][-1]['attributes'].update(inputTokens=20, outputTokens=10)
        report['cases'].append(second)
        report.update(sample_count=2, status='FAIL', dataset_sha256=json_hash([row['case_definition'] for row in report['cases']]))
        return report

    def observe(self, report, **kwargs):
        return observation.observe(report, self.activation, self.helper.approved, PUBLISHER_KEY, **kwargs)

    def reviews(self, report):
        reviews = observation.review_template(report)
        for row in reviews['reviews']:
            row.update(reviewer='unit-reviewer', reviewedAt=self.helper.at(0), errorCategory='NONE')
            row['appropriateRefusal'] = {'status': 'NOT_APPLICABLE', 'reason': 'fictional answerable example'}
            row['hardGates'] = {name: {'status': 'PASS', 'reason': 'fictional local proof'} for name in observation.HARD_GATES}
        return reviews

    def test_all_outcomes_unknown_usage_last_snapshot_and_missing_runs_remain_visible(self):
        report = self.batch()
        report['sample_count'] = 3
        result = self.observe(report)
        group = result['versions'][0]
        self.assertEqual(result['decision'], 'HOLD_SCOPE_UNCERTAIN')
        self.assertEqual(result['missingCount'], 1)
        self.assertEqual(group['cancelledRate'], .5)
        self.assertEqual(group['requestLatency']['p95Ms'], 955)
        self.assertEqual(group['analystUsage']['observedCount'], 0)
        self.assertIsNone(group['analystUsage']['totalTokens'])
        self.assertEqual(group['finalAnswerUsage']['observedInputTokens'], 29)
        self.assertEqual(group['finalAnswerUsage']['completeCount'], 1)
        self.assertIsNone(group['finalAnswerUsage']['totalTokens'])
        self.assertEqual(group['quality'], {'NO_DATA': 2})
        rendered = json.dumps(result, ensure_ascii=False)
        self.assertNotIn(report['cases'][0]['answer'], rendered)
        self.assertNotIn('internal-one', rendered)
        self.assertNotIn(report['cases'][0]['case_definition']['question'], rendered)

    def test_bound_quality_refusal_and_hard_failures_withdraw_without_erasing_history(self):
        report = self.batch()
        reviews = self.reviews(report)
        reviews['reviews'][0]['appropriateRefusal'] = {'status': 'FAIL', 'reason': 'fictional inappropriate refusal'}
        reviews['reviews'][1]['hardGates']['tenant_isolation'] = {'status': 'FAIL', 'reason': 'fictional isolation failure'}
        result = self.observe(report, quality_reviews=completed_reviews(report, 'FAIL'), reviews=reviews)
        self.assertEqual(result['decision'], 'STOP_EXPANSION_AND_WITHDRAW')
        self.assertIn('INAPPROPRIATE_REFUSAL', result['runs'][0]['hardFailures'])
        self.assertIn('FINAL_ANSWER_QUALITY_FAILED', result['runs'][0]['hardFailures'])
        self.assertIn('HARD_GATE:tenant_isolation', result['runs'][1]['hardFailures'])
        (self.root / 'history.json').write_text('preserve')
        with patch.object(observation.getpass, 'getuser', return_value='local-test-operator'):
            action = observation.withdraw(result, self.root)
        names = action['markers']
        self.assertEqual(action['actor'], 'local-test-operator')
        self.assertEqual(action['from'], dict.fromkeys(names, 'ABSENT'))
        self.assertEqual(action['to'], dict.fromkeys(names, 'PRESENT'))
        self.assertLessEqual(observation._time(action['startedAt']), observation._time(action['at']))
        self.assertEqual(action['reason'], result['decision'])
        self.assertEqual(action['evidenceRefs'], {field: result[field] for field in
            ('reportSha256', 'activationSha256', 'qualityReviewsSha256', 'operationalReviewsSha256')})
        self.assertEqual(action['runtimeWithdrawal'], 'UNVERIFIED')
        repeated = observation.withdraw(result, self.root)
        self.assertEqual(names, repeated['markers'])
        self.assertEqual(repeated['from'], action['to'])
        self.assertTrue(all((self.root / name).is_file() for name in names))
        self.assertEqual((self.root / 'history.json').read_text(), 'preserve')
        with self.assertRaisesRegex(ValueError, 'missing'):
            observation.withdraw(result, self.root / 'absent')
        self.assertIsNone(observation.withdraw({'decision': 'HOLD_SCOPE_UNCERTAIN'}, self.root / 'absent'))
        changed = copy.deepcopy(report)
        changed['cases'][0]['trace']['durationMs'] += 1
        with self.assertRaisesRegex(ValueError, 'different execution'):
            self.observe(changed, reviews=reviews)
        report['cases'][1]['trace']['traceId'] = 'run-1'
        with self.assertRaisesRegex(ValueError, 'Repeated trace'):
            self.observe(report)

    def test_scope_violation_and_baseline_distribution_and_cli_never_expand(self):
        report = self.batch()
        second = report['cases'][1]
        second['trace']['status'] = 'success'
        second['passed'] = True
        method = second['trace']['steps'][2]['attributes']
        baseline = self.helper.package['rollback']
        method['methodBundle'] = {'bundleId': baseline['bundleId'], 'bundleSha256': baseline['bundleSha256']}
        method['analystInvocation']['methodBundle'] = method['methodBundle']
        method['methodSelection']['reason'] = 'ACCOUNT_OUTSIDE_SCOPE'
        second['trace']['userId'] = 'outside-account'
        report['status'] = 'PASS'
        result = self.observe(report, quality_reviews=completed_reviews(report), reviews=self.reviews(report))
        self.assertEqual(result['decision'], 'MAINTAIN_REVIEWED_SCOPE')
        self.assertFalse(result['expansionAuthorized'])
        self.assertEqual([version['shareOfExportedRuns'] for version in result['versions']], [.5, .5])
        report['cases'][0]['trace']['userId'] = 'outside-account'
        self.assertEqual(self.observe(report)['decision'], 'STOP_EXPANSION_AND_WITHDRAW')
        for name, value in {'report': report, 'activation': self.activation, 'approved': self.helper.approved}.items():
            (self.root / (name + '.json')).write_text(json.dumps(value), encoding='utf-8')
        args = ['observe', '--report', str(self.root / 'report.json'), '--activation', str(self.root / 'activation.json'),
                '--approved-artifact', str(self.root / 'approved.json'), '--output', str(self.root / 'out.json'),
                '--withdraw-on-failure', '--control-directory', str(self.root)]
        with patch('sys.argv', args), patch.dict(os.environ, {'STOCKSAGE_EVOLUTION_PUBLISHER_KEY': PUBLISHER_KEY.decode()}), redirect_stdout(io.StringIO()):
            self.assertEqual(observation.main(), 3)
        saved = json.loads((self.root / 'out.json').read_bytes())
        self.assertEqual(saved['operatorMarkers'][0], 'PROHIBIT_ACTIVATION')
        self.assertEqual(saved['operatorMarkers'], saved['operatorAction']['markers'])
        self.assertEqual(saved['operatorAction']['status'], 'WITHDRAWAL_MARKERS_WRITTEN')
        self.assertTrue(saved['operatorAction']['actor'])


if __name__ == '__main__':
    unittest.main()
