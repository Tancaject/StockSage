"""Persist AI source judgments and verify their excerpts; no automatic semantic grader."""
import hashlib
import json
from decimal import Decimal
from pathlib import Path
import sys

HERE = Path(__file__).resolve().parent
OLD = HERE.parent / 'batch-learning-validation-20261002'
sys.path.insert(0, str(HERE.parents[1]))
from evolution_rubric import stage_rubric

def read(p):
    return json.loads(p.read_text(encoding='utf8'))

def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

def line(text, token):
    return next(s for s in text.splitlines() if token in s)

def record(n, arm, stage, observed, support, counter, unknown, *, extras=(), failures=None, notes=(), tokens=None):
    """Arguments are judgments made after reading the full answer and its source."""
    name=f'{n:02}-{arm}-{stage}'
    folder=HERE/name
    run=read(folder/'result.json')
    assert run['status']=='COMPLETED' and run['finishReason']=='stop' and run['doneMarker']
    answer=(folder/'answer.txt').read_text(encoding='utf8')
    trace=read(OLD/f'raw-development-{n:02}/development-{n:02}-1-1.response.json')
    evidence=trace['analysis']['evidenceContext']
    gold=[json.loads(s) for s in (OLD/'gold.jsonl').read_text(encoding='utf8').splitlines()][n-1]
    assert len(observed)==len(gold['facts'])
    facts=[]
    for i,(fact,value) in enumerate(zip(gold['facts'],observed)):
        token=tokens[i] if tokens else (format(int(Decimal(value)),',') if fact['unit']=='million' else value)
        facts.append({'field':fact['field'],'period':fact['period'],'unit':fact['unit'],'observed':value,
                      'expected':fact['value'],'answerQuote':line(answer,token),
                      'status':'PASS' if abs(Decimal(value)-Decimal(fact['value']))<=Decimal('.01') else 'FAIL'})
    checks=[]
    for shown,wanted,token,formula in extras:
        checks.append({'observed':shown,'expected':str(wanted),'formula':formula,'answerQuote':line(answer,token),
                       'status':'PASS' if abs(Decimal(shown)-Decimal(wanted))<=Decimal('.01') else 'FAIL'})
    dimensions={}
    for key,token,reason in (
            ('claim_support',support,'Read against original evidence, not the analyst draft as independent authority.'),
            ('counterevidence',counter,'Relevant opposing signals and comparison boundaries retained.'),
            ('unknowns',unknown,'Unavailable commercial causes or disclosure details remain bounded.')):
        dimensions[key]={'status':'PASS','answerQuote':line(answer,token),'reason':reason}
    dimensions['numeric_period_correctness']={'status':'FAIL' if any(x['status']=='FAIL' for x in facts+checks) else 'PASS',
        'answerQuote':facts[0]['answerQuote'],'reason':'Required facts, source units and fiscal periods reviewed; additional calculations use the frozen absolute tolerance 0.01.'}
    for key,(token,reason) in (failures or {}).items():
        dimensions[key]={'status':'FAIL','answerQuote':line(answer,token),'reason':reason}
    source_tokens={1:['Net Income/(Loss) from Operations','Net Cash Provided by Operating Activities','Depreciation/Amortization of Intangibles'],
        7:['Net Income/(Loss) from Operations','Net Cash Provided by Operating Activities','Pension Settlement Charge','(2) 2025 includes a one-time'],
        5:['Cash provided by operating activities |  | 3,485','Cash provided by operating activities |  | 2,358','Expenditures for property and equipment |  | (2,842)','Expenditures for property and equipment |  | (1,864)'],
        6:['Total current assets','Total current liabilities','Current portion of long-term debt','Long-term debt and other borrowings','Cash and cash equivalents']}[n]
    row={'schema':'fundamentals_two_stage_ai_source_review_v1','origin':'CODEX_NONBLIND_AI_SOURCE_REVIEW_NOT_HUMAN_OR_CALIBRATED_JUDGE',
         'case':n,'caseId':trace['caseId'],'arm':arm,'stage':stage,'releaseEligible':False,'rubric':stage_rubric(stage),
         'binding':{'answerSha256':sha(folder/'answer.txt'),'resultSha256':sha(folder/'result.json'),
                    'requestSha256':sha(folder/'request.json'),'sourceTraceSha256':sha(OLD/f'raw-development-{n:02}/development-{n:02}-1-1.response.json'),
                    'goldSha256':sha(OLD/'gold.jsonl'),'policySha256':sha(OLD/'review-policy.json')},
         'requiredFacts':facts,'additionalNumericChecks':checks,'dimensions':dimensions,
         'requiredPoints':[{'id':p['id'],'requirement':p['text'],'status':'PASS'} for p in gold['requiredPoints']],
         'sourceQuotes':[line(evidence,t) for t in source_tokens],'notes':list(notes),
         'semanticStatus':'PASS' if all(v['status']=='PASS' for v in dimensions.values()) else 'FAIL'}
    (HERE/'reviews').mkdir(exist_ok=True)
    with (HERE/'reviews'/f'{name}.json').open('x',encoding='utf8') as f:
        f.write(json.dumps(row,ensure_ascii=False,indent=2)+'\n')
    print(name,row['semanticStatus'])
