# 官方披露读取与确定性比较

模型工具 `readDisclosure` 与 `compareDisclosures` 通过 [DisclosureDocumentService](../../stocksage-backend/src/main/java/com/stocksage/knowledge/DisclosureDocumentService.java) 调用数据服务的 `/api/document/disclosure/read`、`/api/document/disclosure/compare`。读接口接受明确原文 URL；SEC、巨潮、沪深交易所和港交所之外的站点不在支持范围。它不修改或向知识库写入内容。

## 原文与表格

[读取器](../../stocksage-data-service/app/services/disclosure_reader.py) 仅允许列明的官方主机及公告路径，要求 HTTPS/443、无用户信息，拒绝非公网 DNS 地址与重定向，流式下载最多 20 MB。PDF 和 HTML 解析复用现有可终止进程隔离及文档工作量限制。不要把内部地址、公司 IR 任意链接或来源别名当成可下载公告 URL。

HTML 保留段落和表格单元格顺序，不推断合并单元格。PDF 复用 [PdfParser](../../stocksage-data-service/app/services/pdf_parser.py) 的文字提取与工作量限制，并通过 [PyMuPDF 官方表格 API](https://pymupdf.readthedocs.io/en/latest/page.html#Page.find_tables) 返回检测到的单元格和页码。扫描页不做 OCR；未检测到表格不等于公司没有披露。

响应保留 `documentUrl`、原始文档字节的 `documentHash`、`filingType`、`fetchedAt` 和 `blocks`。每个块含稳定于该文档的 `id/kind/section/text`，PDF 附页码，完整表格附 `rows`。`section` 接受明确章节名或 `block:<id>`；多章匹配返回 `AMBIGUOUS_SECTION`，不自动选第一章。10-Q 的 Part I/Part II 标题保留，防止重复 Item 混淆。

`offset` 是选定内容内的字符偏移；`max_chars` 限制原文字符数。按 `nextOffset` 可连续读取，`partial` 表明本次只包含该块的一部分；部分表格不返回冒充完整的 `rows`。`truncated` 表示本次没有包含全部选定内容。章节列表和每次块数量亦有上限，列表缩减由 `sectionsTruncated` 表达。

## 披露差异

两份文档先完整解析并选择章节，再按标准库 `SequenceMatcher` 比较块序列；只规范空白，不改写数字、否定词或词句。输出 `ADDED/REMOVED`、对应文档 URL/哈希、块 ID、页码与原文。段落移动也可能表现为增删，不据此判断管理层是否改口。`totalChanges` 统计完整比较结果，输出上限及原文摘录另以 `truncated/partial` 标明。缺章、歧义或空提取不能返回“无变化”。

## DEEP 证据展开

DEEP 菜单 `evidence_read` 只接受当前账本中已观察且可用的 `evidenceId`，不接受自由 URL。循环在调用网关前核对当前标的、只读授权及来源，再调用 `local.filing.readDisclosure`。

知识库检索可能把多份申报放在一条证据中；本次只展开首段明确来源，响应和观察都保留 `selectedSource/otherSourceCount`，其他来源没有被读完。`otherSourceCount=-1` 表示账本无法确认其余来源数。新入库父子块保存 `document_url`；旧块缺 URL 时仅按准确 `accession` 在已返回的 SEC 索引中定位，找不到就报缺口，不换成最新申报。历史读取不会凭抓取时间推断财报期间或时效。

来源格式的官方示例：[深交所公告](https://disc.static.szse.cn/download/disc/disk03/finalpage/2026-05-08/312e7317-778d-4b02-93f1-d4e21f70beda.PDF)、[港交所公告](https://www1.hkexnews.hk/listedco/listconews/sehk/2026/0413/2026041301538.pdf)。它们是路径来源说明，不是运行可用性保证。
