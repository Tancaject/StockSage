"""Keep verified analysis evidence in learning requests without repeating the full trace."""
from copy import deepcopy
import json


SCOPE_RULE = """
经验只能修复已核查的局部错误，不得扩大用户任务范围。仅对用户要求的项目，或准备断言其缺失的具体项目，执行必要核查；不能要求遍历、计算或展示整张表，也不能强制添加无关指标、附录或章节。
区分会计分项是否可计算与商业原因是否已披露。只需核查相关项目，未被问及且不影响结论的项目无需主动输出。
如果输入包含旧经验及已观察到的副作用，应保留纠错机制并去掉造成该副作用的操作；这不是修改业务原始方法、输出契约或评分标准的授权。
""".strip()


def compact_request(original: dict, *, revision: dict | None = None) -> dict:
    """Call only after the original trace/feedback bindings have been verified."""
    body = deepcopy(original)
    material = json.loads(body["messages"][1]["content"])
    packets = []
    for item in material["developmentOnly"]:
        trace = item["trace"]
        packets.append({"query": trace["query"],
                        "analysisAnswer": trace["analysis"]["answer"],
                        "analysisEvidence": trace["analysis"]["evidenceContext"],
                        "verifiedFeedback": item["verifiedFeedback"]})
    material["developmentOnly"] = packets
    if revision is not None:
        material["revision"] = revision
    body["messages"][0]["content"] += "\n" + SCOPE_RULE
    body["messages"][1]["content"] = json.dumps(material, ensure_ascii=False)
    return body
