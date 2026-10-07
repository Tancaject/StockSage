"""Verify this completed diagnostic's provenance and recompute descriptive results."""
from collections import Counter
from copy import deepcopy
from decimal import Decimal
import json
from pathlib import Path
import statistics

from review_helpers import read, sha

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
OLD = HERE.parent / 'batch-learning-validation-20261002'


def save(name, value):
    (HERE / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf8')


def main():
    plan = read(HERE / 'plan.json')
    for path, expected in plan['frozenFiles'].items():
        assert sha(REPO / path) == expected, path
    registration = read(HERE / 'business-registration.json')
    for key, filename in [('planSha256', 'plan.json'), ('combinedBundleSha256', 'combined-bundle.json'), ('scopeReviewSha256', 'scope-review.json')]:
        assert registration[key] == sha(HERE / filename)
    baseline = (OLD / 'baseline-method.txt').read_text(encoding='utf8')
    method = read(HERE / 'combined-bundle.json')['method']
    rows = []
    for n in plan['cases']:
        trace_path = OLD / f'raw-development-{n:02}/development-{n:02}-1-1.response.json'
        trace = read(trace_path)
        for arm in ['baseline', 'combined']:
            chain = read(HERE / f'{n:02}-{arm}-chain.json')
            assert chain['analysis'] == chain['finalAnswer'] == 'COMPLETED'
            for stage in ['analysis', 'finalAnswer']:
                name = f'{n:02}-{arm}-{stage}'
                folder = HERE / name
                result, request, call_plan = [read(folder / f'{s}.json') for s in ['result', 'request', 'plan']]
                source_path = HERE / f'input-{name}.json'
                source = read(source_path)
                assert call_plan['requestSha256'] == sha(folder / 'request.json')
                assert call_plan['sourceSha256'] == sha(source_path)
                assert call_plan['deadlineSeconds'] == 600 and call_plan['retries'] == 0
                assert source['provenance']['sourceSha256'] == sha(trace_path)
                expected = deepcopy(trace[stage]['messages'])
                if stage == 'analysis':
                    assert sum(m['text'].count(baseline) for m in expected) == 1
                    if arm == 'combined':
                        for message in expected:
                            message['text'] = message['text'].replace(baseline, method)
                else:
                    draft_path = HERE / f'{n:02}-{arm}-analysis/answer.txt'
                    draft = draft_path.read_text(encoding='utf8')
                    assert sum(m['text'].count(trace['analysis']['answer']) for m in expected) == 1
                    for message in expected:
                        message['text'] = message['text'].replace(trace['analysis']['answer'], draft)
                    assert source['provenance']['analysisAnswerSha256'] == sha(draft_path)
                    chars = sum(len(m['text'].encode('utf-16-le')) // 2 for m in expected)
                    assert chars == chain['promptCharacters'] <= 24000
                assert source[stage]['messages'] == expected
                assert request['messages'] == [{'role':m['role'], 'content':m['text']} for m in expected]
                for key in ['model', 'temperature', 'max_tokens', 'enable_thinking', 'thinking_budget']:
                    assert request[key] == plan['settings'][key]
                assert request['stream'] and request['stream_options']['include_usage']
                assert result['status'] == 'COMPLETED' and result['finishReason'] == 'stop' and result['doneMarker']
                assert result['powerStateRestored'] and result['automaticSleepInhibited']
                review = read(HERE / f'reviews/{name}.json')
                for key, path in [('answerSha256', folder/'answer.txt'), ('resultSha256', folder/'result.json'), ('requestSha256', folder/'request.json'), ('sourceTraceSha256', trace_path), ('goldSha256', OLD/'gold.jsonl'), ('policySha256', OLD/'review-policy.json')]:
                    assert review['binding'][key] == sha(path)
                answer = (folder / 'answer.txt').read_text(encoding='utf8')
                gold = [json.loads(line) for line in (OLD/'gold.jsonl').read_text(encoding='utf8').splitlines()][n-1]
                assert [f['field'] for f in review['requiredFacts']] == [f['field'] for f in gold['facts']]
                assert [f['expected'] for f in review['requiredFacts']] == [f['value'] for f in gold['facts']]
                assert [p['id'] for p in review['requiredPoints']] == [p['id'] for p in gold['requiredPoints']]
                for fact in review['requiredFacts'] + review['additionalNumericChecks']:
                    assert fact['answerQuote'] in answer
                    expected_status = 'PASS' if abs(Decimal(fact['observed'])-Decimal(fact['expected'])) <= Decimal('.01') else 'FAIL'
                    assert fact['status'] == expected_status
                assert all(d['answerQuote'] in answer for d in review['dimensions'].values())
                assert all(q in trace['analysis']['evidenceContext'] for q in review['sourceQuotes'])
                semantic = 'PASS' if all(d['status']=='PASS' for d in review['dimensions'].values()) and all(p['status']=='PASS' for p in review['requiredPoints']) else 'FAIL'
                assert review['semanticStatus'] == semantic
                rows.append({'case':n, 'arm':arm, 'stage':stage, 'technicalStatus':'COMPLETED', 'semanticStatus':semantic, 'durationSeconds':result['durationSeconds'], 'withinHistorical300Seconds':result['durationSeconds']<=300, 'totalTokens':result['usage']['total_tokens'], 'review':f'reviews/{name}.json'})
    assert len(rows) == 16
    summaries = {}
    for arm in ['baseline', 'combined']:
        items = [r for r in rows if r['arm']==arm]
        totals = [sum(r['durationSeconds'] for r in items if r['case']==n) for n in plan['cases']]
        summaries[arm] = {'stages':{s:dict(Counter(r['semanticStatus'] for r in items if r['stage']==s)) for s in ['analysis','finalAnswer']}, 'denominatorPerStage':4, 'meanChainSeconds':statistics.mean(totals), 'medianChainSeconds':statistics.median(totals), 'chainSecondsByCase':dict(zip(map(str,plan['cases']),totals)), 'providerTokens':sum(r['totalTokens'] for r in items)}
    paired = []
    for n in plan['cases']:
        b,c = [next(r['semanticStatus'] for r in rows if r['case']==n and r['arm']==a and r['stage']=='finalAnswer') for a in ['baseline','combined']]
        paired.append({'case':n,'baseline':b,'combined':c,'outcome':'GAIN' if b=='FAIL' and c=='PASS' else 'REGRESSION' if b=='PASS' and c=='FAIL' else 'UNCHANGED'})
    generation = read(HERE / 'group-2/result.json')
    result = {'schema':'combined_two_stage_diagnostic_result_v1', 'status':'COMPLETED_NO_NET_QUALITY_GAIN', 'releaseEligible':False, 'selectedCandidate':None, 'reviewOrigin':'CODEX_NONBLIND_AI_SOURCE_REVIEW_NOT_CALIBRATED_JUDGE', 'realGeneratorCalls':1, 'realBusinessCalls':16, 'automaticRetries':0, 'technicalCompleted':16, 'historical300SecondStageCompletions':sum(r['withinHistorical300Seconds'] for r in rows), 'reservedTransferCasesConsumed':0, 'knownTotalTokens':generation['usage']['total_tokens']+sum(r['totalTokens'] for r in rows), 'generatorTokens':generation['usage']['total_tokens'], 'arms':summaries, 'finalPairs':paired, 'calls':rows, 'limits':['Four development cases, one sample per arm; no causal per-lesson or statistical improvement claim.', 'Fixed-message direct-provider replay; not current Java assembler or live RAG acceptance.', 'Source-based AI judgments are not human labels or measured evaluator accuracy.', 'Latency totals sum model-stage elapsed times, excluding orchestration overhead; not concurrent campaign wall time.']}
    save('result.json',result)
    save('audit.json',{'status':'PASS','checks':['Frozen files and preregistration hashes unchanged','Actual method-only analysis intervention verified','Full draft replacement and final prompt budget verified','Provider request settings, stop/DONE and power restoration verified','All 16 review bindings, source/answer quotes and Decimal comparisons verified','Fixed denominators and token accounting recomputed'], 'calls':16,'reviews':16,'resultSha256':sha(HERE/'result.json'), 'semanticReviewLimit':'This audit verifies evidence bindings and arithmetic, not independent semantic adjudication.'})
    print(json.dumps({k:v for k,v in result.items() if k!='calls'},ensure_ascii=False,indent=2))


if __name__ == '__main__':
    main()
