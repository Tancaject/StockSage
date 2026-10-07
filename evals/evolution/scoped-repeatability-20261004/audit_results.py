"""Recompute the fixed-denominator report after all registered attempts finish."""
from copy import deepcopy
from decimal import Decimal
import json
import statistics

from run import HERE, OLD, REPO, read, sha, verify
from evolution_applicability import select_diagnostic_experiences
from evolution_batch import compose_diagnostic_bundle
from evolution_rubric import stage_rubric


def write(name,value):
    (HERE/name).write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf8')


def main():
    verify()
    plan=read(HERE/'plan.json')
    assert read(HERE/'business-registration.json')['planSha256']==sha(HERE/'plan.json')
    revision=read(HERE/'scope-revision.json')
    catalog={k:set(v) for k,v in revision['catalog'].items()}
    lessons=[read(HERE/f'group-{i}/experience.json') for i in (1,2)]
    baseline=(OLD/'baseline-method.txt').read_text(encoding='utf8')
    contract=read(OLD/'groups.json')[0]['fixedContractSha256']
    for item in read(HERE/'observations.json'):
        selected=select_diagnostic_experiences(lessons,{k:set(v) for k,v in item['observations'].items()},catalog)
        assert [x['recordSha256'] for x in selected]==item['selectedMemberHashes']
        method=compose_diagnostic_bundle('baseline-v1',baseline,selected,contract)['method'] if selected else baseline
        trace=read(OLD/f"raw-development-{item['case']:02}/development-{item['case']:02}-1-1.response.json")
        expected=deepcopy(trace['analysis']['messages'])
        for message in expected:
            message['text']=message['text'].replace(baseline,method)
        assert expected==read(HERE/f"scoped-input-{item['case']:02}.json")['analysis']['messages']
        assert item['sameAsBaseline']==(expected==trace['analysis']['messages'])
    rows=[]
    for job in plan['jobs']:
        n=job['case'];root=HERE/f"repeat-{job['repeat']}"
        trace_path=OLD/f'raw-development-{n:02}/development-{n:02}-1-1.response.json'
        trace=read(trace_path)
        for arm in job['order']:
            chain=read(root/f'{n:02}-{arm}-chain.json')
            for stage in ('analysis','finalAnswer'):
                name=f'{n:02}-{arm}-{stage}';folder=root/name
                if not folder.exists():
                    assert chain[stage]=='NOT_RUN'
                    rows.append({'case':n,'repeat':job['repeat'],'arm':arm,'stage':stage,'technicalStatus':'NOT_RUN','semanticStatus':'NO_DATA','totalTokens':None,'durationSeconds':None})
                    continue
                result=read(folder/'result.json');request=read(folder/'request.json');callplan=read(folder/'plan.json')
                assert callplan['requestSha256']==sha(folder/'request.json')
                assert callplan['sourceSha256']==sha(root/f'input-{name}.json')
                assert callplan['retries']==0 and callplan['deadlineSeconds']==600
                expected=deepcopy(trace[stage]['messages'])
                if stage=='analysis' and arm=='scoped':
                    expected=read(HERE/f'scoped-input-{n:02}.json')['analysis']['messages']
                elif stage=='finalAnswer':
                    draft=(root/f'{n:02}-{arm}-analysis/answer.txt').read_text(encoding='utf8')
                    assert sum(m['text'].count(trace['analysis']['answer']) for m in expected)==1
                    for m in expected:
                        m['text']=m['text'].replace(trace['analysis']['answer'],draft)
                    assert sum(len(m['text'].encode('utf-16-le'))//2 for m in expected)==chain['promptUtf16Characters']<=24000
                assert request['messages']==[{'role':m['role'],'content':m['text']} for m in expected]
                for key in ('model','temperature','max_tokens','thinking_budget','enable_thinking','stream'):
                    assert request[key]==plan['settings'][key]
                assert result.get('powerStateRestored') and result.get('automaticSleepInhibited')
                complete=result['status']=='COMPLETED' and result['finishReason']=='stop' and result['doneMarker']
                semantic='NO_DATA';review=None
                if complete:
                    review=read(root/f'reviews/{name}.json')
                    for key,path in [('answerSha256',folder/'answer.txt'),('requestSha256',folder/'request.json'),('resultSha256',folder/'result.json'),('sourceTraceSha256',trace_path),('goldSha256',OLD/'gold.jsonl'),('policySha256',OLD/'review-policy.json')]:
                        assert review['binding'][key]==sha(path)
                    answer=(folder/'answer.txt').read_text(encoding='utf8')
                    gold=[json.loads(s) for s in (OLD/'gold.jsonl').read_text(encoding='utf8').splitlines()][n-1]
                    assert [(f['field'],f['expected']) for f in review['requiredFacts']]==[(f['field'],f['value']) for f in gold['facts']]
                    assert [(p['id'],p['requirement']) for p in review['requiredPoints']]==[(p['id'],p['text']) for p in gold['requiredPoints']]
                    assert review['rubric']==stage_rubric(stage)
                    for fact in review['requiredFacts']+review['additionalNumericChecks']:
                        assert fact['answerQuote'] in answer
                        assert fact['status']==('PASS' if abs(Decimal(fact['observed'])-Decimal(fact['expected']))<=Decimal('.01') else 'FAIL')
                    assert all(d['answerQuote'] in answer for d in review['dimensions'].values())
                    assert all(q in trace['analysis']['evidenceContext'] for q in review['sourceQuotes'])
                    actual_context='\n'.join(m['content'] for m in request['messages'])
                    assert all(q in actual_context for q in review['sourceQuotes'])
                    semantic='PASS' if all(d['status']=='PASS' for d in review['dimensions'].values()) and all(p['status']=='PASS' for p in review['requiredPoints']) else 'FAIL'
                    assert semantic==review['semanticStatus']
                    assert isinstance(review['historicalSourceErrorObserved'],bool)
                rows.append({'case':n,'repeat':job['repeat'],'arm':arm,'stage':stage,'technicalStatus':'COMPLETED' if complete else 'INCOMPLETE',
                             'semanticStatus':semantic,'totalTokens':(result.get('usage') or {}).get('total_tokens'),
                             'durationSeconds':result['durationSeconds'],'historicalSourceErrorObserved':review['historicalSourceErrorObserved'] if review else None,
                             'review':f"repeat-{job['repeat']}/reviews/{name}.json" if review else None})
    assert len(rows)==16
    arms={};paired=[]
    for arm in plan['arms']:
        own=[r for r in rows if r['arm']==arm]
        complete_chains=[sum(r['durationSeconds'] for r in own if r['case']==n and r['repeat']==rep) for n in plan['cases'] for rep in (1,2)
                         if all(r['technicalStatus']=='COMPLETED' for r in own if r['case']==n and r['repeat']==rep)]
        arms[arm]={'denominatorPerStage':4,'stages':{stage:{status:sum(r['stage']==stage and r['semanticStatus']==status for r in own) for status in ('PASS','FAIL','NO_DATA')} for stage in ('analysis','finalAnswer')},
                   'historicalSourceErrorAnalysisCount':sum(r['stage']=='analysis' and r.get('historicalSourceErrorObserved') is True for r in own),
                   'knownTokens':sum(r['totalTokens'] or 0 for r in own),'unknownUsageCalls':sum(r['totalTokens'] is None and r['technicalStatus']!='NOT_RUN' for r in own),
                   'meanCompleteChainSeconds':statistics.mean(complete_chains) if complete_chains else None,'completeChains':len(complete_chains)}
    for job in plan['jobs']:
        b,c=[next(r['semanticStatus'] for r in rows if r['case']==job['case'] and r['repeat']==job['repeat'] and r['stage']=='finalAnswer' and r['arm']==a) for a in plan['arms']]
        paired.append({**{k:job[k] for k in ('case','repeat')},'baseline':b,'scoped':c,'outcome':'INCONCLUSIVE' if 'NO_DATA' in (b,c) else 'GAIN' if b=='FAIL' and c=='PASS' else 'REGRESSION' if b=='PASS' and c=='FAIL' else 'UNCHANGED'})
    gains=sum(p['outcome']=='GAIN' for p in paired)
    regressions=sum(p['outcome']=='REGRESSION' for p in paired)
    decision='INCONCLUSIVE' if any(p['outcome']=='INCONCLUSIVE' for p in paired) else 'NO_NET_QUALITY_GAIN'
    if decision!='INCONCLUSIVE' and gains>0 and regressions==0:
        decision='DESCRIPTIVE_GAIN_ONLY'
    write('result.json',{'schema':'scoped_repeatability_result_v1','status':'COMPLETED','qualityDecision':decision,'releaseEligible':False,'selectedCandidate':None,
        'reviewOrigin':'CODEX_NONBLIND_SOURCE_REVIEW_NOT_CALIBRATED_EVALUATOR','realBusinessCalls':sum(r['technicalStatus']!='NOT_RUN' for r in rows),
        'technicalCompleted':sum(r['technicalStatus']=='COMPLETED' for r in rows),'generatorCalls':0,'automaticRetries':0,'transferConsumed':0,
        'completedWithinHistorical300Seconds':sum(r['technicalStatus']=='COMPLETED' and r['durationSeconds']<=300 for r in rows),
        'knownTotalTokens':sum(r['totalTokens'] or 0 for r in rows),
        'staticNoninterferenceControls':2,'staticControlsAreSemanticPasses':False,'arms':arms,'finalPairs':paired,'calls':rows,
        'limits':['Engineer-corrected scope metadata, not autonomous operation learning.','Two repetitions of two DEV cases do not estimate general accuracy or statistical significance.',
                  'Fixed-source annotations do not implement runtime extraction or production selection.','Historical source-error recurrence is distinct from all-dimension semantic quality.']})
    write('audit.json',{'status':'PASS','slots':16,'reviewedCompleteCalls':sum(r['technicalStatus']=='COMPLETED' for r in rows),
                       'resultSha256':sha(HERE/'result.json'),'checks':['Frozen dependencies','Scope selection and noninterference','Actual payload and complete-draft replacement','Completion and usage','Review/source/gold binding and arithmetic','Fixed denominator aggregation'],
                       'limits':'No independent semantic adjudication claimed.'})
    print(json.dumps({'arms':arms,'finalPairs':paired},ensure_ascii=False,indent=2))


if __name__=='__main__':
    main()
