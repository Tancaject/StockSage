"""Offline role accounting, interruption and resume checks; all provider data is fictional."""
import copy
import json
import sqlite3
import tempfile
import unittest
from contextlib import closing
from datetime import datetime
from pathlib import Path
from unittest.mock import patch

from evolution_acceptance import ROLES, seal, seal_resource_audit
from evolution_experiment import ExperimentLedger, BudgetStop, action_history_proofs
from ordinary_answer_quality import json_hash
from test_evolution_acceptance import fixture, KEY


def reservations():
    return {'schema': 'fundamentals_resource_reservations_v1', 'experimentId': 'unit-test-experiment',
            'roles': {role: {'maxTokens': 200, 'maxCostUsd': '0.0004', 'inputUsdPerMillion': '1',
                             'outputUsdPerMillion': '2'} for role in ROLES}}


def limited_gate(**limits):
    gate = fixture()[6]
    gate['payload']['policy']['limits'].update(limits)
    gate['payload']['review']['policySha256'] = json_hash(gate['payload']['policy'])
    return seal(gate['payload'], KEY)


def observe(roles, tokens=40):
    return lambda response: [{'role': role, 'runId': response['runId'], 'status': 'COMPLETED',
                              'inputTokens': tokens, 'outputTokens': 10, 'proof': response} for role in roles]


class ResourceLedgerTest(unittest.TestCase):
    def test_action_transitions_are_durable_bound_and_not_recreated_on_resume(self):
        with tempfile.TemporaryDirectory() as folder, patch('evolution_experiment.getpass.getuser', return_value='local-test-operator'):
            path, gate = Path(folder) / 'ledger.sqlite', limited_gate()
            ledger = ExperimentLedger(path, gate, KEY, reservations())
            def complete(request):
                submitted = ledger.snapshot()['events']
                self.assertEqual([(event['from'], event['to']) for event in submitted], [(None, 'SUBMITTED')])
                return request
            response = ledger.call('first', {'runId': 'first'}, ('GENERATOR',), complete, observe(('GENERATOR',)))
            events = ledger.snapshot()['events']
            self.assertEqual([(row['from'], row['to']) for row in events],
                             [(None, 'SUBMITTED'), ('SUBMITTED', 'COMPLETED')])
            restored = ExperimentLedger(path, gate, KEY, reservations())
            self.assertEqual(restored.call('first', response, ('GENERATOR',), lambda _: self.fail('duplicate call'),
                                           observe(('GENERATOR',))), response)
            self.assertEqual(restored.snapshot()['events'], events)
            def lost(_):
                raise TimeoutError('not an auditable response')
            with self.assertRaises(TimeoutError):
                restored.call('lost', {}, ('JUDGE',), lost, observe(('JUDGE',)))
            events = restored.snapshot()['events']
            self.assertEqual((events[-1]['from'], events[-1]['to']), ('SUBMITTED', 'UNKNOWN'))
            self.assertEqual([event['sequence'] for event in events], [1, 2, 3, 4])
            for event in events:
                self.assertEqual(event['actor'], 'local-test-operator')
                self.assertIsNotNone(datetime.fromisoformat(event['at']).utcoffset())
                self.assertTrue(event['reason'])
            exported = restored.export_review(Path(folder) / 'review')
            self.assertEqual(exported['events'], events)
            action_history_proofs(exported)
            missing = copy.deepcopy(exported)
            missing['events'].pop()
            with self.assertRaisesRegex(ValueError, 'states differ'):
                action_history_proofs(missing)
            changed = copy.deepcopy(exported)
            changed['events'][1]['from'] = None
            with self.assertRaisesRegex(ValueError, 'not continuous'):
                action_history_proofs(changed)
            index = json.loads((Path(folder) / 'review/evidence-index.json').read_text(encoding='utf-8'))
            proofs = {digest: (Path(folder) / 'review' / name).read_bytes() for digest, name in index.items()}
            audit = json.loads((Path(folder) / 'review/audit-template.json').read_text(encoding='utf-8'))
            seal_resource_audit(audit, proofs, KEY)
            proofs.pop(events[0]['evidenceRefs'][0])
            with self.assertRaisesRegex(ValueError, 'missing the ledger or an invocation proof'):
                seal_resource_audit(audit, proofs, KEY)
            for event in events:
                for digest in event['evidenceRefs']:
                    proof = json.loads((Path(folder) / 'review/proofs' / (digest + '.json')).read_text(encoding='utf-8'))
                    self.assertEqual(json_hash(proof), digest)

    def test_missing_tampered_and_mechanism_only_gates_do_not_create_a_ledger(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'ledger.sqlite'
            gate = limited_gate()
            bad_signature = copy.deepcopy(gate)
            bad_signature['signature'] = 'f' * 64
            gate['payload']['status'] = 'MECHANISM_READY'
            for artifact in (None, bad_signature, seal(gate['payload'], KEY)):
                with self.subTest(artifact=artifact), self.assertRaises(ValueError):
                    ExperimentLedger(path, artifact, KEY, reservations())
                self.assertFalse(path.exists())

    def test_all_roles_share_spend_and_completed_actions_resume_without_another_call(self):
        with tempfile.TemporaryDirectory() as folder:
            path, gate = Path(folder) / 'experiment.sqlite', limited_gate()
            ledger = ExperimentLedger(path, gate, KEY, reservations())
            expected = {'GENERATOR': 1, 'JUDGE': 1, 'ANALYST': 1, 'FINAL_ANSWER': 1}
            for action, roles in [('generate', ('GENERATOR',)), ('judge', ('JUDGE',)), ('replay', ('ANALYST', 'FINAL_ANSWER'))]:
                request = {'runId': action, 'prompt': 'fictional request'}
                self.assertEqual(ledger.call(action, request, roles, lambda request: request, observe(roles)), request)
                resumed = ExperimentLedger(path, gate, KEY, reservations())
                self.assertEqual(resumed.call(action, request, roles, lambda _: self.fail('duplicate call'), observe(roles)), request)
            snapshot = ledger.snapshot()
            self.assertEqual(snapshot['callsReserved'], expected)
            self.assertEqual(snapshot['tokensObserved'], 200)
            self.assertEqual(snapshot['costUsdEstimated'], '0.00024')
            self.assertEqual(snapshot['status'], 'READY')
            start = ledger.bind_plan({'immutable': 'plan'})
            self.assertEqual(resumed.bind_plan({'immutable': 'plan'}), start)
            with self.assertRaisesRegex(ValueError, 'different input'):
                resumed.call('generate', {'runId': 'changed'}, ('GENERATOR',), lambda _: self.fail('changed request'), observe(('GENERATOR',)))
            altered = reservations()
            altered['roles']['GENERATOR']['maxTokens'] = 199
            with self.assertRaisesRegex(ValueError, 'different E07'):
                ExperimentLedger(path, gate, KEY, altered)

    def test_role_token_and_cost_limits_are_checked_before_submission(self):
        policies = [dict(maxTotalTokens=100), dict(maxExperimentCostUsd='0.0003'),
                    dict(maxModelCalls={**dict.fromkeys(ROLES, 100), 'GENERATOR': 0})]
        for limits in policies:
            with self.subTest(limits=limits), tempfile.TemporaryDirectory() as folder:
                ledger = ExperimentLedger(Path(folder) / 'ledger.sqlite', limited_gate(**limits), KEY, reservations())
                with self.assertRaises(BudgetStop) as stopped:
                    ledger.call('generate', {'runId': 'generate'}, ('GENERATOR',), lambda _: self.fail('budget bypass'), observe(('GENERATOR',)))
                self.assertEqual(stopped.exception.exit_code, 4)
                self.assertFalse(stopped.exception.submitted)
                self.assertEqual(ledger.snapshot()['actions'], [])

    def test_lost_response_abrupt_exit_and_inflight_submission_never_retry(self):
        for error in (TimeoutError, KeyboardInterrupt):
            with self.subTest(error=error), tempfile.TemporaryDirectory() as folder:
                path, gate = Path(folder) / 'ledger.sqlite', limited_gate()
                ledger = ExperimentLedger(path, gate, KEY, reservations())
                def lose_response(_):
                    raise error()
                with self.assertRaises(error):
                    ledger.call('generate', {}, ('GENERATOR',), lose_response, observe(('GENERATOR',)))
                restored = ExperimentLedger(path, gate, KEY, reservations())
                for action in ('generate', 'next'):
                    with self.assertRaisesRegex(BudgetStop, 'PENDING_OR_UNKNOWN'):
                        restored.call(action, {}, ('GENERATOR',), lambda _: self.fail('ambiguous call repeated'), observe(('GENERATOR',)))
                snapshot = restored.snapshot()
                self.assertEqual(snapshot['tokensAccounted'], 200)
                self.assertIsNone(snapshot['tokensObserved'])
                self.assertIsNone(snapshot['costUsdEstimated'])
        with tempfile.TemporaryDirectory() as folder:
            ledger = ExperimentLedger(Path(folder) / 'ledger.sqlite', limited_gate(), KEY, reservations())
            def concurrent(_):
                with self.assertRaisesRegex(BudgetStop, 'PENDING_OR_UNKNOWN'):
                    ledger.call('other', {}, ('JUDGE',), lambda _: self.fail('concurrent call'), observe(('JUDGE',)))
                return {'runId': 'first'}
            ledger.call('first', {}, ('GENERATOR',), concurrent, observe(('GENERATOR',)))
            self.assertEqual(ledger.snapshot()['status'], 'READY')

    def test_unknown_usage_overrun_and_stop_preserve_prior_results_and_stop_new_calls(self):
        for kind in ('unknown', 'overrun', 'invalid-inspector'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as folder:
                path, gate = Path(folder) / 'ledger.sqlite', limited_gate()
                ledger = ExperimentLedger(path, gate, KEY, reservations())
                inspector = observe(('GENERATOR',), None if kind == 'unknown' else 300)
                if kind == 'invalid-inspector':
                    inspector = lambda _: []
                result = ledger.call('generate', {}, ('GENERATOR',), lambda _: {'runId': 'generate'}, inspector)
                restored = ExperimentLedger(path, gate, KEY, reservations())
                self.assertEqual(restored.call('generate', {}, ('GENERATOR',), lambda _: self.fail('retried'), inspector), result)
                self.assertEqual(restored.snapshot()['status'], 'BLOCKED')
                with self.assertRaises(BudgetStop):
                    restored.call('new', {}, ('JUDGE',), lambda _: self.fail('continued after unknown usage'), observe(('JUDGE',)))
        with tempfile.TemporaryDirectory() as folder:
            ledger = ExperimentLedger(Path(folder) / 'ledger.sqlite', limited_gate(), KEY, reservations())
            with self.assertRaisesRegex(BudgetStop, 'OPERATOR_STOP'):
                ledger.call('new', {}, ('JUDGE',), lambda _: self.fail('ignored stop'), observe(('JUDGE',)), stop_requested=lambda: True)
            self.assertEqual(ledger.snapshot()['actions'], [])

    def test_bad_rate_reservation_is_rejected_without_creating_a_ledger(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'ledger.sqlite'
            bad = copy.deepcopy(reservations())
            bad['roles']['GENERATOR']['maxCostUsd'] = '0.00001'
            with self.assertRaisesRegex(ValueError, 'maximum token charge'):
                ExperimentLedger(path, limited_gate(), KEY, bad)
            self.assertFalse(path.exists())
            with closing(sqlite3.connect(path)) as db, db:
                db.execute('CREATE TABLE invocation (run_id TEXT PRIMARY KEY)')
                db.execute("INSERT INTO invocation VALUES ('previous-paid-call')")
            with self.assertRaisesRegex(ValueError, 'another contract'):
                ExperimentLedger(path, limited_gate(), KEY, reservations())
            with closing(sqlite3.connect(path)) as db:
                self.assertEqual(db.execute('SELECT run_id FROM invocation').fetchall(), [('previous-paid-call',)])

    def test_audit_export_retains_unknown_calls_and_rejects_dropped_generator_cost(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            ledger = ExperimentLedger(root / 'ledger.sqlite', limited_gate(), KEY, reservations())
            ledger.call('generate', {}, ('GENERATOR',), lambda _: {'runId': 'generate'}, observe(('GENERATOR',)))
            with self.assertRaises(TimeoutError):
                ledger.call('judge', {}, ('JUDGE',), lambda _: (_ for _ in ()).throw(TimeoutError()), observe(('JUDGE',)))
            ledger.export_review(root / 'review')
            index = json.loads((root / 'review/evidence-index.json').read_text(encoding='utf-8'))
            proofs = {digest: (root / 'review' / name).read_bytes() for digest, name in index.items()}
            audit = json.loads((root / 'review/audit-template.json').read_text(encoding='utf-8'))
            self.assertFalse(audit['complete'])
            self.assertEqual({row['role'] for row in audit['entries']}, {'GENERATOR', 'JUDGE'})
            self.assertIn('UNKNOWN', [row['status'] for row in audit['entries']])
            seal_resource_audit(audit, proofs, KEY)
            audit['entries'] = audit['entries'][1:]
            with self.assertRaisesRegex(ValueError, 'cannot omit'):
                seal_resource_audit(audit, proofs, KEY)


if __name__ == '__main__':
    unittest.main()
