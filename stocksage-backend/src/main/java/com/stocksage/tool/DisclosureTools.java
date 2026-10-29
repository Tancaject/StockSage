package com.stocksage.tool;

import com.stocksage.knowledge.DisclosureDocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DisclosureTools {
    private final DisclosureDocumentService disclosureDocumentService;

    @Tool(description = "读取指定官方公告原文或表格。只支持 SEC Archives、巨潮、沪深交易所、港交所公告链接；保留原文版本、章节、页码和块 ID。长文按 nextOffset 继续读取；扫描 PDF 不做 OCR，未提取到表格不代表未披露。")
    public String readDisclosure(
            @ToolParam(description = "已有证据或公告搜索返回的明确官方原文 URL") String url,
            @ToolParam(description = "10-K、10-Q、8-K 或 DISCLOSURE") String filingType,
            @ToolParam(description = "章节名称，或 block:<块ID>；空字符串读取全文开头；歧义时按 availableSections 指定") String section,
            @ToolParam(description = "true 只读检测到的表格；false 读正文和表格") boolean tablesOnly,
            @ToolParam(description = "所选正文的字符偏移；首次 0，后续使用 nextOffset") int offset,
            @ToolParam(description = "本次返回原文字符数上限，256–24000，通常 12000") int maxChars) {
        return disclosureDocumentService.read(url, filingType, section, tablesOnly, offset, maxChars);
    }

    @Tool(description = "对两份明确披露的同一章节做确定性段落/表格增删比较。返回旧、新 URL、哈希、块ID和原文，不判断管理层是否改口。章节缺失、解析失败或截断必须保留说明。")
    public String compareDisclosures(
            @ToolParam(description = "较早披露的官方原文 URL") String olderUrl,
            @ToolParam(description = "较新披露的官方原文 URL") String newerUrl,
            @ToolParam(description = "10-K、10-Q、8-K 或 DISCLOSURE") String filingType,
            @ToolParam(description = "两份文档中明确的同一章节；空字符串比较全文，过大时须指定章节") String section,
            @ToolParam(description = "最多返回增删块数，1–50，通常 20") int maxChanges) {
        return disclosureDocumentService.compare(olderUrl, newerUrl, filingType, section, maxChanges);
    }
}
