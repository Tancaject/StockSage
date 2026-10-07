"""Bounded paired replay of unchanged lesson operations with reviewed scope metadata."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
OLD = HERE.parent / 'batch-learning-validation-20261002'
PREVIOUS = HERE.parent / 'unit-generalization-network-20261004'
sys.path.insert(0, str(REPO / 'evals'))
from evolution_applicability import scope_issues, select_diagnostic_experiences
from evolution_batch import compose_diagnostic_bundle
from evolution_candidates import build_experience


def read(path):
    return json.loads(path.read_text(encoding='utf8'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('x', encoding='utf8') as out:
        out.write(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def verify():
    for path, digest in read(HERE/'plan.json')['frozenFiles'].items():
        assert sha(REPO/path) == digest, path


def prepare():
    parents = [HERE.parent/'learning-revision-20261003/group-1/experience.json', PREVIOUS/'group-2/experience.json']
    lessons = [read(p) for p in parents]
    catalog = {'triggerTags':['cumulative-to-single-quarter-reconstruction','unit-conversion-billion-to-million'],
               'requiredEvidence':['two-cumulative-periods-same-start-date','cash-flow-statement-line-items','source-footnote-unit','source-table-unit'],
               'requiredCapabilities':['evidence-reading','period-comparison','unit-comparison','arithmetic']}
    cat = {k:set(v) for k,v in catalog.items()}
    specs = []
    for i,lesson in enumerate(lessons,1):
        spec = {k:deepcopy(v) for k,v in lesson.items() if k not in ['schemaVersion','kind','status','reviewStatus','validationStatus','methodSha256','experienceId','recordSha256']}
        spec['version'] += 1
        spec['parentExperienceHashes'] = [lesson['recordSha256']]
        if i == 1:
            spec['triggerTags'] = ['cumulative-to-single-quarter-reconstruction']
        else:
            spec['triggerTags'] = ['unit-conversion-billion-to-million']
            spec['applicabilityBoundary'] = '仅在用户要求使用的源文件金额以billion披露、而目标展示单位为百万或中文亿时适用；舍入步骤仅在该任务涉及派生比率时执行。'
            spec['counterexamples'] = ['任务所需金额均已使用目标展示单位，无需换算', '不同单位只存在于与当前问题无关的脚注']
        artifacts = {}
        for member in read(OLD/'groups.json')[i-1]['members']:
            for field,hfield in [('tracePath','traceSha256'),('feedbackPath','feedbackSha256')]:
                artifacts[member[hfield]] = (OLD/member[field]).read_text(encoding='utf8')
        revised = build_experience(spec,artifacts,parents=[lesson])
        assert revised['method'] == lesson['method']
        assert not scope_issues(revised,cat)
        save(HERE/f'group-{i}/experience.json',revised)
        specs.append(revised)
    save(HERE/'scope-revision.json',{'origin':'ENGINEER_SCOPE_METADATA_REVISION_NOT_AUTONOMOUS_LEARNING',
        'operationTextUnchanged':True,'catalog':catalog,
        'parentScopeIssues':[scope_issues(p,cat) for p in lessons],
        'corrections':['Remove post-answer error label from pre-answer group-1 trigger.',
                       'Narrow group-2 dispatch to required unit conversion, rather than an unexecutable OR condition; optional rounding stays in the unchanged operation.'],
        'observationProducer':'CODEX_PREANSWER_SOURCE_REVIEW_OF_FIXED_DEV_INPUTS_NOT_RUNTIME_EXTRACTOR',
        'productionCompatible':False,
        'runtimeGap':'OrdinaryEvidence produces only fundamentals/reportPeriod/period-comparison task tags and financial-evidence/dated-values/structured-financials evidence tags.',
        'capabilityLimit':'arithmetic denotes declared reasoning ability; FundamentalsAgent has no calculator tool.'})
    cases = [json.loads(s) for s in (OLD/'cases.jsonl').read_text(encoding='utf8').splitlines()]
    observations = []
    baseline = (OLD/'baseline-method.txt').read_text(encoding='utf8')
    contract = read(OLD/'groups.json')[0]['fixedContractSha256']
    for n in [1,5,6,7]:
        case = cases[n-1]
        task, evidence = [], []
        if n == 5:
            assert '根据给出的年内累计现金流表还原' in case['query']
            assert len({e['periodStart'] for e in case['evidence']}) == 1
            assert [e['periodEnd'] for e in case['evidence']] == ['2025-11-01','2025-08-02']
            task = ['cumulative-to-single-quarter-reconstruction']
            evidence = ['two-cumulative-periods-same-start-date','cash-flow-statement-line-items']
        if n == 7:
            assert '税项按脚注已披露的约数计算' in case['query']
            assert '$0.3 billion' in case['evidence'][0]['visibleText'] and '(Dollars in Millions)' in case['evidence'][0]['visibleText']
            task = ['unit-conversion-billion-to-million']
            evidence = ['source-footnote-unit','source-table-unit']
        observed = {'triggerTags':task,'requiredEvidence':evidence,'requiredCapabilities':catalog['requiredCapabilities']}
        selected = select_diagnostic_experiences(specs,{k:set(v) for k,v in observed.items()},cat)
        assert [e['recordSha256'] for e in selected] == ([] if n in [1,6] else [specs[0 if n==5 else 1]['recordSha256']])
        method = compose_diagnostic_bundle('baseline-v1',baseline,selected,contract)['method'] if selected else baseline
        trace_path = OLD/f'raw-development-{n:02}/development-{n:02}-1-1.response.json'
        trace = read(trace_path)
        messages = deepcopy(trace['analysis']['messages'])
        assert sum(m['text'].count(baseline) for m in messages)==1
        for m in messages:
            m['text'] = m['text'].replace(baseline,method)
        if not selected:
            assert messages == trace['analysis']['messages']
        save(HERE/f'scoped-input-{n:02}.json',{'analysis':{'messages':messages},'sourceSha256':sha(trace_path)})
        observations.append({'case':n,'caseId':case['caseId'],'query':case['query'],
            'sourceTraceSha256':sha(trace_path),'observations':observed,
            'selectedMemberHashes':[x['recordSha256'] for x in selected],
            'sameAsBaseline':messages==trace['analysis']['messages'],
            'basis':('Same fiscal start and distinct cumulative ends; question explicitly requests reconstruction.' if n==5 else
                     'Question requires tax footnotes in billion combined with a table in million.' if n==7 else
                     'Required answer uses already disclosed quarter values or balance sheet million values; no cumulative reconstruction or required footnote-unit conversion.')})
    save(HERE/'observations.json',observations)
    frozen = dict(read(PREVIOUS/'plan.json')['frozenFiles'])
    paths = [HERE/'run.py',HERE/'scope-revision.json',HERE/'observations.json',REPO/'evals/evolution_applicability.py',
             REPO/'evals/test_evolution_applicability.py',PREVIOUS/'result.json',PREVIOUS/'review_helpers.py',*parents]
    paths += list(HERE.glob('group-*/experience.json')) + list(HERE.glob('scoped-input-*.json'))
    for path in paths:
        frozen[path.relative_to(REPO).as_posix()] = sha(path)
    for path,digest in frozen.items():
        assert sha(REPO/path)==digest,path
    save(HERE/'plan.json',{'schema':'scoped_repeatability_diagnostic_v1','registeredAt':datetime.now(timezone.utc).isoformat(),
        'frozenFiles':frozen,'cases':[5,7],'repetitions':2,'arms':['baseline','scoped'],
        'jobs':[{'case':5,'repeat':1,'order':['baseline','scoped']},{'case':7,'repeat':1,'order':['scoped','baseline']},
                {'case':5,'repeat':2,'order':['scoped','baseline']},{'case':7,'repeat':2,'order':['baseline','scoped']}],
        'maxBusinessCalls':16,'generatorCalls':0,'automaticRetries':0,'maxConcurrentChains':2,
        'settings':{'model':'qwen3.8-max','temperature':0.7,'max_tokens':4096,'thinking_budget':32768,'enable_thinking':True,'stream':True,'deadlineSeconds':600},
        'authority':'USER_CONTINUATION_OF_AUTHORIZED_PUBLIC_SOURCE_AI_DIAGNOSTIC',
        'releaseEligible':False,'selectedCandidate':None,'transferCasesConsumed':0,
        'quality':'Unchanged fundamentals four dimensions, gold and review policy. Source-bound nonblind Codex review, not calibrated evaluation-Agent accuracy. Include extra claims. NO_DATA for incomplete response; no retries or denominator deletion.',
        'completion':'Only COMPLETED + stop + DONE analysis enters final; replace full draft once; final prompt <=24000 UTF-16 units.',
        'decision':'Report fixed 4 final attempts per arm, paired changes, two observations per case and historical original-error recurrence separately. No selection, significance or stable gain claim with two repetitions. Any observed regression or non-positive net gain precludes a positive improvement conclusion.',
        'controls':'Cases 01 and 06 must select zero members and preserve exact baseline message bytes. They are static non-interference checks, not fresh semantic passes.',
        'intervention':'Scope metadata manually corrected with unchanged learned operation text; select one relevant lesson from verified pre-answer DEV observations. Compare against fresh baseline, not against historical combined outputs as if randomized.',
        'limits':['Offline labels do not implement runtime extraction. Existing generated tags are not supported by production observation producer.',
                  'No numeric gold or expected answer sent to business model; evidence and question remain original.',
                  'Only public statements, synthetic questions and derived AI lesson/analysis content sent to the previously authorized DashScope endpoint.'],
        'stopRule':'Finish the fixed budget regardless of quality; on infrastructure failure preserve attempts, never retry automatically.'})


def business():
    verify()
    save(HERE/'business-registration.json',{'planSha256':sha(HERE/'plan.json'),'startedAt':datetime.now(timezone.utc).isoformat()})
    probe = REPO/'evals/diagnostics/model-root-cause-20261002/probe.py'
    def call(root,n,arm,stage,messages):
        source = root/f'input-{n:02}-{arm}-{stage}.json'
        save(source,{'schema':'DERIVED_FROZEN_MESSAGES_NOT_JAVA_RUNTIME',stage:{'messages':messages}})
        folder = root/f'{n:02}-{arm}-{stage}'
        with (root/f'{n:02}-{arm}-{stage}.log').open('x',encoding='utf8') as log:
            subprocess.run([sys.executable,'-X','utf8',str(probe),'--source',str(source),'--stage',stage,'--output',str(folder),'--deadline-seconds','600'],stdout=log,stderr=subprocess.STDOUT,check=True)
        result = read(folder/'result.json')
        print(json.dumps({'repeat':root.name,'case':n,'arm':arm,'stage':stage,'status':result['status'],'seconds':result['durationSeconds']},ensure_ascii=False),flush=True)
        return result,folder
    def complete(result):
        return result['status']=='COMPLETED' and result['finishReason']=='stop' and result['doneMarker']
    def pair(job):
        n=job['case'];root=HERE/f"repeat-{job['repeat']}"
        trace = read(OLD/f'raw-development-{n:02}/development-{n:02}-1-1.response.json')
        for arm in job['order']:
            messages = trace['analysis']['messages'] if arm=='baseline' else read(HERE/f'scoped-input-{n:02}.json')['analysis']['messages']
            result,folder=call(root,n,arm,'analysis',messages)
            chain={'analysis':result['status'],'finalAnswer':'NOT_RUN','releaseEligible':False}
            if complete(result):
                draft=(folder/'answer.txt').read_text(encoding='utf8')
                final=deepcopy(trace['finalAnswer']['messages'])
                assert sum(m['text'].count(trace['analysis']['answer']) for m in final)==1
                for m in final:
                    m['text']=m['text'].replace(trace['analysis']['answer'],draft)
                chars=sum(len(m['text'].encode('utf-16-le'))//2 for m in final)
                chain['promptUtf16Characters']=chars
                if chars<=24000:
                    final_result,_=call(root,n,arm,'finalAnswer',final)
                    chain['finalAnswer']='COMPLETED' if complete(final_result) else 'INCOMPLETE'
                else:
                    chain['reason']='Full final prompt exceeds frozen budget'
            else:
                chain['analysis']='INCOMPLETE'
                chain['reason']='Analysis lacks complete stop/DONE termination'
            save(root/f'{n:02}-{arm}-chain.json',chain)
    with ThreadPoolExecutor(max_workers=2) as pool:
        list(pool.map(pair,read(HERE/'plan.json')['jobs']))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['prepare','verify','business'])
    args=parser.parse_args()
    {'prepare':prepare,'verify':verify,'business':business}[args.command]()
