"""Bounded combined-experience diagnostic using saved business message templates."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
PREVIOUS = HERE.parent / 'learning-revision-20261003'
OLD = HERE.parent / 'batch-learning-validation-20261002'
spec = importlib.util.spec_from_file_location('previous_learning_revision', PREVIOUS / 'run.py')
prior = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prior)
prior.HERE = HERE
read, save, digest = prior.read, prior.save, prior.digest
from evolution_learning_input import compact_request
from evolution_batch import compose_diagnostic_bundle

UNIT_RULE = '''单位类经验只写可迁移的操作关系：以符号表示金额，通过单位倍率先统一数量级，再生成所有展示形式，并核对这些形式是否等值。不能把源任务中的任何具体金额作为例子，也不能换成另一个示例金额；不要在经验正文中出现阿拉伯数字。倍率可用中文文字表达，不能因此省略具体的换算核查步骤。
纠错对象仅为经来源核实的分析错误。旧建议因包含原题金额被拒绝，必须解决该内容问题，不能放宽校验或改变评分。舍入规则服从当前问题，不自行要求某个固定精度。'''


def prepare():
    frozen = {}
    for previous in (OLD, PREVIOUS):
        for path, expected in read(previous / 'plan.json')['frozenFiles'].items():
            assert digest(REPO / path) == expected, path
            frozen[path] = expected
    request = compact_request(read(OLD / 'generated/group-2/request.json'), revision={
        'rejectedProposal': json.loads(read(PREVIOUS / 'group-2/response.json')['choices'][0]['message']['content']),
        'feedback': '通用方法夹带原题具体金额，被内容范围校验拒绝。改为符号金额和通用单位关系，保持原来的内容边界。',
        'origin': 'ENGINEER_REVIEW_OF_REJECTED_GENERATION'})
    request['messages'][0]['content'] += '\n' + UNIT_RULE
    save(HERE / 'group-2/request.json', request)
    paths = [HERE/'run.py', HERE/'group-2/request.json', PREVIOUS/'group-1/experience.json',
             PREVIOUS/'group-2/response.json', PREVIOUS/'group-2/validation-diagnostic.json',
             OLD/'review-policy.json', OLD/'gold.jsonl', REPO/'evals/evolution_rubric.py',
             REPO/'evals/diagnostics/model-root-cause-20261002/probe.py']
    paths += [OLD/f'raw-development-{n:02}/development-{n:02}-1-1.response.json' for n in (7,5,1,6)]
    frozen.update({p.relative_to(REPO).as_posix():digest(p) for p in paths})
    save(HERE/'plan.json', {'schema':'unit_generalization_combined_chain_diagnostic_v1',
        'registeredAt':datetime.now(timezone.utc).isoformat(), 'frozenFiles':frozen,
        'authority':'USER_AUTHORIZED_AI_DIAGNOSTIC_ONLY', 'releaseEligible':False,
        'generatorCalls':1, 'maxBusinessCalls':16, 'maxConcurrentChains':3, 'automaticRetries':0,
        'cases':[7,5,1,6], 'armOrder':[['baseline','combined'],['combined','baseline'],['baseline','combined'],['combined','baseline']],
        'members':['learning-revision-20261003/group-1/experience.json','group-2/experience.json'],
        'scope':'Development-only exploratory combination; previous per-member screening and formal selection gates are not claimed. Two source errors plus original fixed controls. No transfer or activation.',
        'settings':{'model':'qwen3.8-max','temperature':0.7,'max_tokens':4096,'enable_thinking':True,'thinking_budget':32768,'deadlineSeconds':600,'historicalDeadlineSeconds':300},
        'execution':'Direct provider calls with actual saved analysis/final business message templates. Only analysis method changes by arm; final template replaces its one original draft with the complete newly generated draft. Not execution of current Java assembler or online RAG.',
        'completion':'Require completed stream, finish_reason=stop and DONE for analysis before final. Otherwise final is NOT_RUN and stays in denominator. No local truncation or retry. Full final messages must remain within 24000 UTF-16 code units.',
        'quality':'Freeze existing fundamentals four dimensions, gold and source-review policy. Review analysis and final independently with source and answer excerpts. Required numbers and extra claims count. Timeout/incomplete is NO_DATA, not semantic FAIL or PASS. Nonblind AI source review, not calibrated Ordinary judge accuracy.',
        'decision':'Report all four paired outcomes, repairs and regressions separately. No selection in this campaign. Positive descriptive net final PASS with no new material numeric/factual failures is a promising signal only; any failures or lack of benefit remain explicit. No per-lesson causal attribution from combined results.',
        'stopRule':'If generation or static scope review fails, stop without business calls. Otherwise finish fixed cases even when an arm fails; no retries, edits or extra questions to manufacture improvement.'})


def compose():
    prior.verify()
    assert read(HERE/'scope-review.json')['approvedForDiagnostic'] is True
    members = [read(PREVIOUS/'group-1/experience.json'),read(HERE/'group-2/experience.json')]
    group = read(OLD/'groups.json')[1]
    method = (OLD/'baseline-method.txt').read_text(encoding='utf8')
    save(HERE/'combined-bundle.json',compose_diagnostic_bundle('baseline-v1',method,members,group['fixedContractSha256']))


def business():
    prior.verify()
    bundle=read(HERE/'combined-bundle.json')
    baseline=(OLD/'baseline-method.txt').read_text(encoding='utf8')
    plan=read(HERE/'plan.json')
    save(HERE/'business-registration.json',{'planSha256':digest(HERE/'plan.json'),
         'combinedBundleSha256':digest(HERE/'combined-bundle.json'), 'scopeReviewSha256':digest(HERE/'scope-review.json'),
         'registeredAt':datetime.now(timezone.utc).isoformat()})
    probe=REPO/'evals/diagnostics/model-root-cause-20261002/probe.py'
    def call(n,arm,stage,messages,provenance):
        name=f'{n:02}-{arm}-{stage}'
        source=HERE/f'input-{name}.json'
        save(source,{'schema':'DERIVED_DIAGNOSTIC_MESSAGES_NOT_JAVA_RESPONSE',stage:{'messages':messages},'provenance':provenance})
        with (HERE/f'{name}.log').open('x',encoding='utf8') as log:
            subprocess.run([sys.executable,'-X','utf8',str(probe),'--source',str(source),'--stage',stage,'--output',str(HERE/name),'--deadline-seconds','600'],stdout=log,stderr=subprocess.STDOUT,check=True)
        result=read(HERE/name/'result.json')
        print(json.dumps({'call':name,**result}),flush=True)
        return result,HERE/name
    def complete(r):
        return r['status']=='COMPLETED' and r['finishReason']=='stop' and r['doneMarker']
    def pair(item):
        n,order=item
        trace_path=OLD/f'raw-development-{n:02}/development-{n:02}-1-1.response.json'
        trace=read(trace_path)
        for arm in order:
            messages=deepcopy(trace['analysis']['messages'])
            assert sum(m['text'].count(baseline) for m in messages)==1
            if arm=='combined':
                for m in messages:m['text']=m['text'].replace(baseline,bundle['method'])
            provenance={'source':trace_path.relative_to(REPO).as_posix(),'sourceSha256':digest(trace_path),'caseId':trace['caseId'],'arm':arm}
            analysis,folder=call(n,arm,'analysis',messages,provenance)
            if not complete(analysis):
                save(HERE/f'{n:02}-{arm}-chain.json',{'analysis':'INCOMPLETE','finalAnswer':'NOT_RUN','reason':'Analysis not completely terminated','releaseEligible':False})
                continue
            draft=(folder/'answer.txt').read_text(encoding='utf8')
            final=deepcopy(trace['finalAnswer']['messages'])
            previous=trace['analysis']['answer']
            assert sum(m['text'].count(previous) for m in final)==1
            for m in final:m['text']=m['text'].replace(previous,draft)
            chars=sum(len(m['text'].encode('utf-16-le'))//2 for m in final)
            if chars>24000:
                save(HERE/f'{n:02}-{arm}-chain.json',{'analysis':'COMPLETED','finalAnswer':'NOT_RUN','reason':'Full final prompt exceeds frozen 24000-character budget','promptCharacters':chars,'releaseEligible':False})
                continue
            result,final_folder=call(n,arm,'finalAnswer',final,{**provenance,'analysisAnswerSha256':digest(folder/'answer.txt'),'finalPromptUtf16Characters':chars})
            save(HERE/f'{n:02}-{arm}-chain.json',{'analysis':'COMPLETED','finalAnswer':'COMPLETED' if complete(result) else 'INCOMPLETE','promptCharacters':chars,'releaseEligible':False})
    with ThreadPoolExecutor(max_workers=3) as pool:
        list(pool.map(pair,zip(plan['cases'],plan['armOrder'])))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['prepare','generate','compose','business','verify'])
    args=parser.parse_args()
    {'prepare':prepare,'generate':lambda:prior.generate(2),'compose':compose,'business':business,'verify':prior.verify}[args.command]()
