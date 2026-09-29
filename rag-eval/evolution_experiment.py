"""Shared evaluator-owned resource ledger for bounded evolution experiments.

Frozen reservations are planning limits, not a provider billing guarantee.
An interrupted submission may still be in flight and is never retried automatically.
"""
from __future__ import annotations

import getpass
import json
import re
import sqlite3
from decimal import Decimal
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

from ordinary_answer_quality import json_hash

ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,119}")
SEARCH_TRANSITIONS = {
    None: {'CREATED'}, 'CREATED': {'VALIDATED'}, 'VALIDATED': {'RUNNING'},
    'RUNNING': {'RUNNING', 'PENDING_GENERATION', 'PENDING_DEVELOPMENT', 'PENDING_VALIDATION',
                'BLOCKED', 'CANCELLED', 'BUDGET_EXHAUSTED', 'COMPLETED'},
    **{state: {'RUNNING'} for state in ('PENDING_GENERATION', 'PENDING_DEVELOPMENT', 'PENDING_VALIDATION',
                                       'BLOCKED', 'CANCELLED', 'BUDGET_EXHAUSTED')},
    'COMPLETED': set(),
}
CANDIDATE_TRANSITIONS = {
    None: {'PROPOSED'}, 'PROPOSED': {'VALIDATED', 'REJECTED_STATIC'},
    'VALIDATED': {'PENDING_DEVELOPMENT', 'EVALUATED_DEVELOPMENT'},
    'PENDING_DEVELOPMENT': {'EVALUATED_DEVELOPMENT'},
    'EVALUATED_DEVELOPMENT': {'REJECTED_DEVELOPMENT', 'PENDING_VALIDATION', 'EVALUATED_VALIDATION'},
    'PENDING_VALIDATION': {'EVALUATED_VALIDATION'},
    'EVALUATED_VALIDATION': {'REJECTED_VALIDATION', 'NOMINATED'},
    'REJECTED_STATIC': set(), 'REJECTED_DEVELOPMENT': set(), 'REJECTED_VALIDATION': set(), 'NOMINATED': set(),
}


class BudgetStop(ValueError):
    def __init__(self, reason, *, exit_code=3, submitted=False):
        super().__init__(reason)
        self.reason, self.exit_code, self.submitted = reason, exit_code, submitted


def resource_entries(snapshot: dict) -> list[dict]:
    entries = []
    for action in snapshot['actions']:
        if action['charges']:
            entries.extend(action['charges'])
        else:
            # Missing responses keep all reserved roles in the independent audit.
            entries.extend({'invocationId': action['actionId'] + ':' + role, 'runId': action['actionId'],
                            'role': role, 'status': 'UNKNOWN', 'inputTokens': None, 'outputTokens': None,
                            'costUsd': None, 'proofSha256': json_hash(action)} for role in action['roles'])
    return entries


def action_history_proofs(snapshot: dict) -> set[str]:
    """An audit must retain every recorded transition and resolve its original evidence."""
    from evolution_dataset import exact_fields, nonempty, timestamp
    from evolution_acceptance import require_hash
    actions = {row['actionId']: row['status'] for row in snapshot['actions']}
    events, states, proofs = snapshot.get('events'), {}, set()
    if not isinstance(events, list) or len(actions) != len(snapshot['actions']):
        raise ValueError('Model action audit history is absent or has duplicate actions')
    for sequence, event in enumerate(events, 1):
        exact_fields(event, {'sequence', 'actionId', 'from', 'to', 'actor', 'at', 'reason', 'evidenceRefs'}, 'model action event')
        previous = states.get(event['actionId'])
        if (type(event['sequence']) is not int or event['sequence'] != sequence or event['actionId'] not in actions
                or event['from'] != previous or event['to'] not in ({'SUBMITTED'} if previous is None
                    else {'UNKNOWN', 'COMPLETED'} if previous == 'SUBMITTED' else set())):
            raise ValueError('Model action audit history is not continuous')
        for name in ('actor', 'reason'):
            nonempty(event[name], 'model action event ' + name)
        timestamp(event['at'], 'model action event time')
        if not isinstance(event['evidenceRefs'], list) or not event['evidenceRefs']:
            raise ValueError('Model action audit history requires original evidence')
        for digest in event['evidenceRefs']:
            require_hash(digest, 'model action event evidence')
            proofs.add(digest)
        states[event['actionId']] = event['to']
    if states != actions:
        raise ValueError('Model action states differ from their audit history')
    return proofs


def search_history_proofs(snapshot: dict) -> set[str]:
    from evolution_dataset import exact_fields, nonempty, timestamp
    from evolution_acceptance import require_hash
    states, proofs = {}, set()
    events = snapshot.get('searchEvents')
    if not isinstance(events, list):
        raise ValueError('Search audit history must be a list')
    for sequence, event in enumerate(events, 1):
        exact_fields(event, {'sequence', 'searchId', 'entityId', 'entityType', 'from', 'to', 'actor', 'at',
                             'reason', 'evidenceRefs'}, 'search event')
        require_hash(event['searchId'], 'search manifest hash')
        for name in ('entityId', 'actor', 'reason'):
            nonempty(event[name], 'search event ' + name)
        timestamp(event['at'], 'search event time')
        subject = (event['searchId'], event['entityId'])
        previous = states.get(subject)
        transitions = SEARCH_TRANSITIONS if event['entityType'] == 'SEARCH' else CANDIDATE_TRANSITIONS if event['entityType'] == 'CANDIDATE' else {}
        if (type(event['sequence']) is not int or event['sequence'] != sequence or event['from'] != previous
                or event['to'] not in transitions.get(previous, set())):
            raise ValueError('Search/candidate audit history is not continuous')
        if not isinstance(event['evidenceRefs'], list) or not event['evidenceRefs']:
            raise ValueError('Search audit history requires original evidence')
        for digest in event['evidenceRefs']:
            require_hash(digest, 'search event evidence')
            proofs.add(digest)
        states[subject] = event['to']
    return proofs


class ExperimentLedger:
    """One evaluator-owned experiment; all model roles share the accepted E07 limits.

    Completed responses are durable input for rebuilding reports, never new repeats.
    SUBMITTED may still be in flight or have lost its response; neither is retried.
    """
    def __init__(self, path: Path, gate_artifact: dict, key: bytes, reservations: dict):
        from evolution_acceptance import ROLES, require_gate, money
        from evolution_dataset import exact_fields
        self.gate = require_gate(gate_artifact, key)
        self.actor = getpass.getuser()
        self.gate_hash = gate_artifact['payloadSha256']
        self.limits = self.gate['policy']['limits']
        exact_fields(reservations, {'schema', 'experimentId', 'roles'}, 'resource reservations')
        if (reservations['schema'] != 'fundamentals_resource_reservations_v1'
                or reservations['experimentId'] != self.gate['experimentId']):
            raise ValueError('Resource reservations belong to a different experiment')
        exact_fields(reservations['roles'], set(ROLES), 'role reservations')
        for role, row in reservations['roles'].items():
            exact_fields(row, {'maxTokens', 'maxCostUsd', 'inputUsdPerMillion', 'outputUsdPerMillion'}, role)
            if type(row['maxTokens']) is not int or row['maxTokens'] < 1:
                raise ValueError('Each role needs a positive token reservation')
            cost = money(row['maxCostUsd'], 'reservation cost', nullable=True)
            rates = [money(row[name], name, nullable=True) for name in ('inputUsdPerMillion', 'outputUsdPerMillion')]
            if self.limits['maxExperimentCostUsd'] is not None and (cost is None or None in rates):
                raise ValueError('A monetary limit requires frozen prices and a reservation for every role')
            if cost is not None and None not in rates and cost < row['maxTokens'] * max(rates) / Decimal(1000000):
                raise ValueError('Cost reservation cannot be less than the maximum token charge')
        # Freeze caller-owned dictionaries so a callback cannot change admission rules.
        self.reservations = json.loads(json.dumps(reservations))
        self.limits = json.loads(json.dumps(self.limits))
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        identity = json_hash({'gate': gate_artifact['payloadSha256'], 'reservations': reservations})
        with closing(sqlite3.connect(self.path)) as db, db:
            tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if tables and 'budget_contract' not in tables:
                raise ValueError('Existing ledger uses another contract; independently account for its calls before starting a new experiment')
            db.execute('CREATE TABLE IF NOT EXISTS budget_contract (singleton INTEGER PRIMARY KEY CHECK(singleton=1), identity TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS budget_plan (identity TEXT PRIMARY KEY, started_at TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS budget_action (id TEXT PRIMARY KEY, request_hash TEXT NOT NULL, roles TEXT NOT NULL, '
                       'status TEXT NOT NULL, result TEXT, charges TEXT, error_type TEXT, submitted_at TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS budget_proof (hash TEXT PRIMARY KEY, content TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS budget_event (sequence INTEGER PRIMARY KEY, action_id TEXT NOT NULL, event TEXT NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS search_event (sequence INTEGER PRIMARY KEY, search_id TEXT NOT NULL, '
                       'entity_id TEXT NOT NULL, event_key TEXT, event TEXT NOT NULL, UNIQUE(search_id,entity_id,event_key))')
            db.execute('INSERT OR IGNORE INTO budget_contract VALUES (1, ?)', (identity,))
            if db.execute('SELECT identity FROM budget_contract WHERE singleton=1').fetchone()[0] != identity:
                raise ValueError('Ledger is already bound to a different E07 gate or frozen reservation/rate card')
        self.identity = identity

    def search_event(self, search_id, entity_id, kind, state, reason, proof, *, milestone=None):
        """Candidate milestones are immutable; resuming them never rewinds their current state."""
        # ponytail: one search controller per ledger; add an owner lease if concurrent controllers are needed.
        digest = json_hash(proof)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('BEGIN IMMEDIATE')
            registered = db.execute('SELECT search_id FROM search_event LIMIT 1').fetchone()
            if registered and registered[0] != search_id:
                raise ValueError('Ledger is already bound to another search manifest; obtain a new independently reviewed experiment')
            if milestone is not None:
                prior = db.execute('SELECT event FROM search_event WHERE search_id=? AND entity_id=? AND event_key=?',
                                   (search_id, entity_id, milestone)).fetchone()
                if prior:
                    saved = json.loads(prior[0])
                    if (saved['to'] != state or saved['entityType'] != kind or saved['reason'] != reason
                            or saved['evidenceRefs'] != [digest]):
                        raise ValueError('Recorded search milestone changed; preserve the original experiment')
                    return
            prior = db.execute('SELECT event FROM search_event WHERE search_id=? AND entity_id=? ORDER BY sequence DESC LIMIT 1',
                               (search_id, entity_id)).fetchone()
            previous = json.loads(prior[0])['to'] if prior else None
            transitions = SEARCH_TRANSITIONS if kind == 'SEARCH' else CANDIDATE_TRANSITIONS if kind == 'CANDIDATE' else {}
            if state not in transitions.get(previous, set()):
                raise ValueError('Illegal search/candidate transition: ' + str(previous) + ' -> ' + state)
            db.execute('INSERT OR IGNORE INTO budget_proof VALUES (?, ?)',
                       (digest, json.dumps(proof, ensure_ascii=False, allow_nan=False)))
            event = {'searchId': search_id, 'entityId': entity_id, 'entityType': kind, 'from': previous, 'to': state,
                     'actor': self.actor, 'at': datetime.now(timezone.utc).isoformat(), 'reason': reason, 'evidenceRefs': [digest]}
            db.execute('INSERT INTO search_event (search_id,entity_id,event_key,event) VALUES (?, ?, ?, ?)',
                       (search_id, entity_id, milestone, json.dumps(event, ensure_ascii=False)))

    def completed_search(self, search_id):
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('BEGIN')
            row = db.execute('SELECT event FROM search_event WHERE search_id=? AND entity_id=? ORDER BY sequence DESC LIMIT 1',
                             (search_id, search_id)).fetchone()
            event = json.loads(row[0]) if row else None
            if event and event['to'] == 'COMPLETED':
                proof = db.execute('SELECT content FROM budget_proof WHERE hash=?', (event['evidenceRefs'][0],)).fetchone()
                if proof is None or json_hash(json.loads(proof[0])) != event['evidenceRefs'][0]:
                    raise ValueError('Completed search evidence is missing or changed')
                return json.loads(proof[0])['result']
            return None

    def _event(self, db, action_id, state, reason, proof):
        """Commit audit history with the action mutation; restoring a response creates no new transition."""
        prior = db.execute('SELECT event FROM budget_event WHERE action_id=? ORDER BY sequence DESC LIMIT 1',
                           (action_id,)).fetchone()
        previous = json.loads(prior[0])['to'] if prior else None
        if (state == 'SUBMITTED' and previous is not None
                or state in {'UNKNOWN', 'COMPLETED'} and previous != 'SUBMITTED'):
            raise ValueError('Model action audit history is absent or inconsistent; preserve the original ledger for review')
        digest = json_hash(proof)
        db.execute('INSERT OR IGNORE INTO budget_proof VALUES (?, ?)',
                   (digest, json.dumps(proof, ensure_ascii=False, allow_nan=False)))
        event = {'actionId': action_id, 'from': previous, 'to': state, 'actor': self.actor,
                 'at': datetime.now(timezone.utc).isoformat(), 'reason': reason, 'evidenceRefs': [digest]}
        db.execute('INSERT INTO budget_event (action_id,event) VALUES (?, ?)',
                   (action_id, json.dumps(event, ensure_ascii=False)))

    def bind_plan(self, plan: dict) -> str:
        """Keep original report start time when rebuilding the same predeclared plan."""
        identity = json_hash(plan)
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('INSERT OR IGNORE INTO budget_plan VALUES (?, ?)', (identity, datetime.now(timezone.utc).isoformat()))
            return db.execute('SELECT started_at FROM budget_plan WHERE identity=?', (identity,)).fetchone()[0]

    def _snapshot(self, db) -> dict:
        from evolution_acceptance import ROLES, money
        calls, tokens, cost, actual_tokens, actual_cost = dict.fromkeys(ROLES, 0), 0, Decimal(0), 0, Decimal(0)
        reason, actions, accounted_cost_known = None, [], True
        for action_id, roles_json, status, result, charges_json, error_type, at in db.execute(
                'SELECT id,roles,status,result,charges,error_type,submitted_at FROM budget_action ORDER BY submitted_at,id'):
            roles, charges = json.loads(roles_json), json.loads(charges_json) if charges_json else []
            if status != 'COMPLETED':
                reason = reason or 'SUBMISSION_PENDING_OR_UNKNOWN'
            indexed = {row['role']: row for row in charges}
            for role in roles:
                calls[role] += 1
                reserve, charge = self.reservations['roles'][role], indexed.get(role, {})
                used = [charge.get('inputTokens'), charge.get('outputTokens')]
                known_tokens = all(type(value) is int and value >= 0 for value in used)
                used_tokens = sum(used) if known_tokens else reserve['maxTokens']
                billed = money(charge.get('costUsd'), 'recorded cost', nullable=True)
                reserved_cost = money(reserve['maxCostUsd'], 'reserved cost', nullable=True)
                tokens += used_tokens
                cost += billed if billed is not None else reserved_cost or 0
                accounted_cost_known &= billed is not None or reserved_cost is not None
                actual_tokens = actual_tokens + sum(used) if actual_tokens is not None and known_tokens else None
                actual_cost = actual_cost + billed if actual_cost is not None and billed is not None else None
                if not known_tokens or billed is None:
                    reason = reason or 'INVOCATION_USAGE_OR_PRICE_UNKNOWN'
                elif used_tokens > reserve['maxTokens'] or (reserved_cost is not None and billed > reserved_cost):
                    reason = reason or 'ACTUAL_USAGE_RESERVATION_EXCEEDED'
            actions.append({'actionId': action_id, 'status': status, 'roles': roles, 'charges': charges,
                            'errorType': error_type, 'submittedAt': at,
                            'resultSha256': json_hash(json.loads(result)) if result is not None else None})
        if (tokens > self.limits['maxTotalTokens'] or any(calls[role] > self.limits['maxModelCalls'][role] for role in ROLES)
                or (self.limits['maxExperimentCostUsd'] is not None and cost > money(self.limits['maxExperimentCostUsd'], 'cost limit'))):
            reason = reason or 'EXPERIMENT_BUDGET_EXCEEDED'
        return {'schema': 'fundamentals_resource_ledger_v1', 'experimentId': self.gate['experimentId'],
                'acceptanceGateSha256': self.gate_hash,
                'contractSha256': self.identity, 'status': 'BLOCKED' if reason else 'READY', 'reason': reason,
                'callsReserved': calls, 'tokensAccounted': tokens, 'tokensObserved': actual_tokens,
                'costUsdReservedOrEstimated': str(cost) if accounted_cost_known else None,
                'costUsdEstimated': str(actual_cost) if actual_cost is not None else None,
                'pricingBasis': 'FROZEN_RATE_CARD_ESTIMATE', 'actions': actions,
                'events': [{'sequence': sequence, **json.loads(event)}
                           for sequence, event in db.execute('SELECT sequence,event FROM budget_event ORDER BY sequence')],
                'searchEvents': [{'sequence': sequence, **json.loads(event)}
                                 for sequence, event in db.execute('SELECT sequence,event FROM search_event ORDER BY sequence')]}

    def snapshot(self) -> dict:
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('BEGIN')
            return self._snapshot(db)

    @staticmethod
    def _recorded(db, action_id, request_hash):
        prior = db.execute('SELECT request_hash,status,result FROM budget_action WHERE id=?', (action_id,)).fetchone()
        if not prior:
            return False, None
        if prior[0] != request_hash:
            raise ValueError('Action ID is already bound to different input or roles')
        if prior[1] != 'COMPLETED':
            raise BudgetStop('SUBMISSION_PENDING_OR_UNKNOWN', submitted=True)
        return True, json.loads(prior[2])

    def recorded(self, action_id, request, roles):
        with closing(sqlite3.connect(self.path)) as db:
            return self._recorded(db, action_id, json_hash({'request': request, 'roles': roles}))

    def export_review(self, directory: Path) -> dict:
        """Export complete recorded charges/proofs for independent completeness review."""
        directory = Path(directory)
        directory.mkdir(parents=True, exist_ok=False)
        (directory / 'proofs').mkdir()
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('BEGIN')
            snapshot = self._snapshot(db)
            proofs = {digest: json.loads(content) for digest, content in db.execute('SELECT hash,content FROM budget_proof')}
        for action in snapshot['actions']:
            if not action['charges']:
                proofs[json_hash(action)] = action
        entries = resource_entries(snapshot)
        template = {'kind': 'RESOURCE_AUDIT', 'experimentId': self.gate['experimentId'], 'gateSha256': self.gate_hash,
                    'ledgerSha256': json_hash(snapshot), 'complete': False, 'reviewer': None, 'reviewedAt': None,
                    'candidateCount': None, 'searchRounds': None, 'entries': entries,
                    'runOutcomes': {row['runId']: 'UNREVIEWED' for row in entries if row['role'] == 'ANALYST'}}
        index = {json_hash(snapshot): 'ledger.json'}
        values = {'ledger.json': snapshot, 'audit-template.json': template}
        for digest, proof in proofs.items():
            name = 'proofs/' + digest + '.json'
            index[digest], values[name] = name, proof
        values['evidence-index.json'] = index
        for name, value in values.items():
            with (directory / name).open('x', encoding='utf-8') as output:
                output.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
        return snapshot

    def call(self, action_id: str, request: dict, roles: tuple[str, ...], invoke, inspect, *, stop_requested=lambda: False):
        """inspect(response) yields one role/runId/status/token/proof object per reservation.

        The evaluator supplies adapters; model text is never treated as usage or price.
        Role count and conservative ceilings are committed before any remote call.
        """
        from evolution_acceptance import money
        if (not isinstance(action_id, str) or not ID.fullmatch(action_id) or not roles
                or len(set(roles)) != len(roles) or any(role not in self.reservations['roles'] for role in roles)):
            raise ValueError('Declare an action ID and distinct supported model roles')
        request_hash = json_hash({'request': request, 'roles': roles})
        with closing(sqlite3.connect(self.path)) as db, db:
            db.execute('BEGIN IMMEDIATE')
            found, result = self._recorded(db, action_id, request_hash)
            if found:
                return result
            current = self._snapshot(db)
            if current['reason']:
                raise BudgetStop(current['reason'])
            if stop_requested():
                raise BudgetStop('OPERATOR_STOP')
            tokens = sum(self.reservations['roles'][role]['maxTokens'] for role in roles)
            cost = sum((money(self.reservations['roles'][role]['maxCostUsd'], 'reserved cost', nullable=True) or Decimal(0)
                        for role in roles), Decimal(0))
            if (current['tokensAccounted'] + tokens > self.limits['maxTotalTokens']
                    or any(current['callsReserved'][role] + 1 > self.limits['maxModelCalls'][role] for role in roles)
                    or (self.limits['maxExperimentCostUsd'] is not None and
                        Decimal(current['costUsdReservedOrEstimated']) + cost > money(self.limits['maxExperimentCostUsd'], 'cost limit'))):
                raise BudgetStop('NEXT_ACTION_EXCEEDS_FROZEN_BUDGET', exit_code=4)
            db.execute('INSERT INTO budget_action VALUES (?, ?, ?, ?, NULL, NULL, NULL, ?)',
                       (action_id, request_hash, json.dumps(roles), 'SUBMITTED', datetime.now(timezone.utc).isoformat()))
            self._event(db, action_id, 'SUBMITTED', 'FROZEN_BUDGET_RESERVED',
                        {'request': request, 'roles': roles, 'gateSha256': self.gate_hash, 'contractSha256': self.identity})
        try:
            response = invoke(request)
            encoded = json.dumps(response, ensure_ascii=False, allow_nan=False)
        except Exception as error:
            with closing(sqlite3.connect(self.path)) as db, db:
                db.execute("UPDATE budget_action SET status='UNKNOWN',error_type=? WHERE id=?", (type(error).__name__, action_id))
                self._event(db, action_id, 'UNKNOWN', 'REMOTE_OUTCOME_UNKNOWN',
                            {'requestSha256': request_hash, 'errorType': type(error).__name__})
            raise
        charges, proofs, inspection_error = [], {}, None
        try:
            observations = inspect(response)
            if len(observations) != len(roles) or {row['role'] for row in observations} != set(roles):
                raise ValueError('Usage must account for every reserved role exactly once')
            for row in observations:
                if (row['status'] not in {'COMPLETED', 'FAILED', 'UNKNOWN'} or not ID.fullmatch(row['runId'])
                        or any(value is not None and (type(value) is not int or value < 0)
                               for value in (row['inputTokens'], row['outputTokens']))):
                    raise ValueError('Invalid model invocation usage')
                rate = self.reservations['roles'][row['role']]
                values = (row['inputTokens'], row['outputTokens'], rate['inputUsdPerMillion'], rate['outputUsdPerMillion'])
                cost = None if None in values else (row['inputTokens'] * Decimal(rate['inputUsdPerMillion'])
                        + row['outputTokens'] * Decimal(rate['outputUsdPerMillion'])) / Decimal(1000000)
                charges.append({'invocationId': action_id + ':' + row['role'], 'role': row['role'], 'runId': row['runId'],
                                'status': row['status'], 'inputTokens': row['inputTokens'], 'outputTokens': row['outputTokens'],
                                'costUsd': str(cost) if cost is not None else None, 'proofSha256': json_hash(row['proof'])})
                proofs[json_hash(row['proof'])] = json.dumps(row['proof'], ensure_ascii=False, allow_nan=False)
        except (ValueError, TypeError, KeyError) as error:
            charges, inspection_error = [], type(error).__name__
        with closing(sqlite3.connect(self.path)) as db, db:
            db.executemany('INSERT OR IGNORE INTO budget_proof VALUES (?, ?)', proofs.items())
            db.execute("UPDATE budget_action SET status='COMPLETED',result=?,charges=?,error_type=? WHERE id=?",
                       (encoded, json.dumps(charges), inspection_error, action_id))
            self._event(db, action_id, 'COMPLETED', 'RESPONSE_RECORDED_USAGE_UNVERIFIED' if inspection_error else 'RESPONSE_RECORDED',
                        {'requestSha256': request_hash, 'response': response, 'charges': charges, 'inspectionError': inspection_error})
        return response


def replay_usage(replay: dict) -> list[dict]:
    from evolution_acceptance import provider_usage
    result = []
    for stage, role in (('analysis', 'ANALYST'), ('finalAnswer', 'FINAL_ANSWER')):
        proof = replay.get(stage) or {}
        usage = provider_usage(proof, stage)
        result.append({'role': role, 'runId': replay['runId'],
                       'status': 'COMPLETED' if proof.get('status') == 'COMPLETED' else 'FAILED',
                       'inputTokens': usage[0] if usage else None, 'outputTokens': usage[1] if usage else None, 'proof': proof})
    return result
