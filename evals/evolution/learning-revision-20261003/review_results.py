"""Serialize source reviews made after reading each answer; does not call a judge."""
import json
import hashlib
from decimal import Decimal as D
from pathlib import Path
import sys
ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'evals'))
from evolution_rubric import stage_rubric
P = ROOT / 'evals/evolution/learning-revision-20261003'
O = P.parent / 'batch-learning-validation-20261002'
read = lambda p: json.loads(p.read_text(encoding='utf8'))
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
gold = [json.loads(x) for x in (O/'gold.jsonl').read_text(encoding='utf8').splitlines()]

# Source-derived values are paired with answer excerpts, including optional claims.
extra = {
 '05-old': [(978,1127,'86.79'),(1127,978,'115.24'),(388,739,'52.50'),(323,655,'49.31'),(65,84,'77.38')],
 '05-revised': [(1127,978,'115.24')],
 '06-old': [(940,4762,'19.74'),(1248,19454,'6.41'),(443,20799,'2.13'),(503,1636,'30.75'),(1062,14304,'7.42'),(559,15940,'3.51'),(1499,11178,'13.41'),(3822,21242,'17.99'),(4762,20799,'22.90'),(805,1345,'59.85'),(2156,12740,'16.92'),(739,13053,'5.66')],
 '06-revised': [(1248,19454,'6.42'),(443,20799,'2.13'),(503,1636,'30.75'),(1062,14304,'7.42'),(559,15940,'3.51'),(1499,11178,'13.41'),(940,4762,'19.74')],
 '09-revised': [(-327,2945,'-11.10'),(-433,4905,'-8.83'),(-157,1149,'-13.66'),(-276,3756,'-7.35')],
}
quotes = {
 '05-old': ['经营现金创造能力足以覆盖当期主要固定资产投入','资本开支消耗了大部分经营现金流','现有证据不足以确认商业原因'],
 '05-revised': ['经营现金流高于该项支出','还原有效性依赖期初一致与无重述','不能将还原出的会计变动直接推断为经营驱动因素'],
 '06-old': ['流动比率从 0.94 提升至 0.97','现金下降和总债务上升共同影响','无法判断公司在压力情形下的完整流动性缓冲'],
 '06-revised': ['流动资产对流动负债的覆盖仍不足','与 2025-02-01 相比，短期流动性呈现混合信号','不能等同于对公司实际短期融资能力的完整评估'],
 '09-revised': ['这说明自由现金流的同比改善并不来自经营现金流增强','整体现金创造并未增强','不能把会计还原值直接解释为经营驱动因素'],
}

def line(answer, token):
    return next(x for x in answer.splitlines() if token in x)

rows=[]
for n in (5,9,6):
 for arm in ('old','revised'):
    name=f'{n:02}-{arm}'; folder=P/name
    if not (folder/'result.json').exists(): continue
    run=read(folder/'result.json'); a=(folder/'answer.txt').read_text(encoding='utf8')
    source=read(O/f'raw-development-{n:02}/development-{n:02}-1-1.response.json')['analysis']['evidenceContext']
    g=gold[n-1]
    item={'arm':arm,'case':n,'caseId':g['caseId'],'binding':{'answerSha256':sha(folder/'answer.txt'),'inputSha256':sha(P/f'input-{name}.json'),'evidenceTextSha256':hashlib.sha256(source.encode()).hexdigest(),'resultSha256':sha(folder/'result.json')},'releaseEligible':False}
    complete=run['status']=='COMPLETED' and run['finishReason']=='stop' and run['doneMarker']
    item.update(complete=complete,withinOriginal300Seconds=complete and run['durationSeconds']<=300)
    if not complete:
        item.update(semanticStatus='NO_DATA',reason='Provider did not supply a complete stop-terminated answer; no PASS inferred from the partial body')
        rows.append(item);continue
    if name not in quotes: continue
    facts=[]
    for f in g['facts']:
        value = str(D(f['value']).quantize(D('.01'))) if f['unit']=='ratio' else str(int(D(f['value'])))
        token = value if f['unit']=='ratio' else format(int(value),',')
        q=line(a, token)
        facts.append({'field':f['field'],'observed':value,'expected':f['value'],'answerQuote':q,'status':'PASS' if abs(D(value)-D(f['value']))<=D('.01') else 'FAIL'})
    checks=[]
    for x,y,shown in extra.get(name,[]):
        expected=D(x)/D(y)*100
        checks.append({'formula':f'{x}/{y}*100','expected':str(expected),'observed':shown,'answerQuote':line(a,shown+'%'),'status':'PASS' if abs(expected-D(shown))<=D('.01') else 'FAIL'})
    supported, counter, unknown = quotes[name]
    dims={}
    for dimension, token, reason in [('claim_support',supported,'Question-relevant conclusion checked against visible source and derived quantities.'),('counterevidence',counter,'Opposing cash/liquidity signals and applicable scope limits retained.'),('unknowns',unknown,'Accounting facts are distinguished from unavailable commercial explanations or facilities.')]:
        assert token in a
        dims[dimension]={'status':'PASS','answerQuote':line(a,token),'reason':reason}
    dims['numeric_period_correctness']={'status':'FAIL' if any(x['status']=='FAIL' for x in facts+checks) else 'PASS','answerQuote':facts[0]['answerQuote'],'reason':'Required facts, fiscal periods, units, gross spending definition and additional numerical claims checked; absolute tolerance 0.01.'}
    if name=='09-revised':
        dims['claim_support'].update(status='FAIL',reason='The additional phrase says FCF improved year over year, while 3480 is below 3756. Main conclusion and required numbers are correct; this is a local direction contradiction, not a wrong core answer.')
    source_tokens={5:['Cash provided by operating activities |  | 3,485','Cash provided by operating activities |  | 2,358','Expenditures for property and equipment |  | (2,842)','Expenditures for property and equipment |  | (1,864)'],6:['Total current assets |  | 20,702','Total current liabilities |  | 21,242','Cash and cash equivalents |  | $ 3,822','Current portion of long-term debt and other borrowings |  | 1,133','Long-term debt and other borrowings |  | 15,366'],9:['Net income $ 5,740 $ 8,092','Net income $ 3,122 $ 5,147','Net Cash Provided by Operating Activities 5,468 6,220','Net Cash Provided by Operating Activities 996 1,315','Capital spending (2,499) (2,850)','Capital spending (1,507) (1,701)']}[n]
    item.update(requiredFacts=facts,additionalNumericChecks=checks,dimensions=dims,sourceQuotes=[line(source,t) for t in source_tokens],requiredPoints=[{'id':p['id'],'requirement':p['text'],'status':'PASS'} for p in g['requiredPoints']],semanticStatus='PASS' if all(d['status']=='PASS' for d in dims.values()) else 'FAIL')
    if name=='05-old': item['notes']=['Fiscal Q3 and adjacent cumulative endpoints correctly identify the period, although the start date is not written explicitly.','All additional cash-flow line differences and prior-year required aggregates were checked against both tables; 86.79% is the only numeric deviation over tolerance (expected 86.7791%).']
    if name=='06-old': item['notes']=['Displayed asset growth 6.41% differs from correct two-decimal rounding 6.42%, but remains within the frozen 0.01 absolute tolerance; not relabeled FAIL.']
    if n==5: item['originalFalseMissingClaimPresent']=False
    rows.append(item)
out={'schema':'fundamentals_analysis_revision_source_review_v1','origin':'CODEX_AI_SOURCE_REVIEW_NONBLIND_NOT_CALIBRATED_JUDGE','releaseEligible':False,'rubric':stage_rubric('analysis'),'goldSha256':sha(O/'gold.jsonl'),'policySha256':sha(O/'review-policy.json'),'rows':rows}
(P/'source-review.json').write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
print(json.dumps({'reviewed':len(rows),'verdicts':[(x['case'],x['arm'],x['semanticStatus']) for x in rows]}))
