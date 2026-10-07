"""Persist explicitly source-read judgments; this is not an automatic grader."""
from decimal import Decimal as D
import importlib.util
from pathlib import Path
import json

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('source_review_helpers',HERE.parent/'unit-generalization-network-20261004/review_helpers.py')
helpers=importlib.util.module_from_spec(spec)
spec.loader.exec_module(helpers)


def record(repetition,case,arm,stage,support,counter,unknown,*,extras=(),failures=None,notes=(),original_error=False,tokens=None,observed=None):
    helpers.HERE=HERE/f'repeat-{repetition}'
    values=observed or (['1127','978','149'] if case==5 else ['5293','5134','1.73','1.78','1.83','2.93'])
    helpers.record(case,arm,stage,values,support,counter,unknown,extras=extras,failures=failures,notes=notes,tokens=tokens)
    path=helpers.HERE/f'reviews/{case:02}-{arm}-{stage}.json'
    review=helpers.read(path)
    review['historicalSourceErrorObserved']=original_error
    review['repetition']=repetition
    path.write_text(json.dumps(review,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
