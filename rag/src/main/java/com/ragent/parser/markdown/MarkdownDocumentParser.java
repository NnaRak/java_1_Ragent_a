package com.ragent.parser.markdown;

import com.ragent.parser.DocumentParser;
import com.ragent.parser.model.DocBlock;
import com.ragent.parser.model.ParsedDocument;
import com.ragent.parser.model.RawDocument;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableRow;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BulletList;
import org.commonmark.node.Code;
import org.commonmark.node.CustomBlock;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.HardLineBreak;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SoftLineBreak;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MarkdownDocumentParser implements DocumentParser {

    private static final Parser PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create()))
            .build();

    @Override
    public String parserType() {
        return "markdown";
    }

    @Override
    public Set<String> supportedExtensions() {
        return Set.of("md", "markdown", "mdown", "mkdn", "mkd");
    }

    @Override
    public Set<String> supportedMimeTypes() {
        return Set.of("text/markdown", "text/x-markdown", "text/x-web-markdown");
    }

    @Override
    public ParsedDocument parse(RawDocument document) {
        String title = baseTitle(document.filename());
        if (document.bytes().length == 0) {
            return ParsedDocument.empty(title, ParsedDocument.DocumentKind.MARKDOWN);
        }
        Node root = PARSER.parse(new String(document.bytes(), StandardCharsets.UTF_8));
        BlockVisitor visitor = new BlockVisitor();
        root.accept(visitor);
        String documentTitle = visitor.firstH1 == null ? title : visitor.firstH1;
        return new ParsedDocument(documentTitle, ParsedDocument.DocumentKind.MARKDOWN, visitor.blocks, Map.of(
                "parser", parserType(),
                "mimeType", document.contentType(),
                "blocks", visitor.blocks.size()));
    }

    private static String baseTitle(String filename) {
        String name = filename == null ? "" : filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return StringUtils.hasText(name) ? name : "README";
    }

    private static final class BlockVisitor extends AbstractVisitor {

        private final List<DocBlock> blocks = new ArrayList<>();
        private String firstH1;

        @Override
        public void visit(Heading heading) {
            String text = inlineText(heading);
            if (text.isBlank()) {
                return;
            }
            blocks.add(new DocBlock.Heading(heading.getLevel(), text));
            if (heading.getLevel() == 1 && firstH1 == null) {
                firstH1 = text;
            }
        }

        @Override
        public void visit(Paragraph paragraph) {
            if (paragraph.getParent() instanceof ListItem) {
                return;
            }
            String text = inlineText(paragraph);
            if (!text.isBlank()) {
                blocks.add(new DocBlock.Paragraph(text));
            }
        }

        @Override
        public void visit(FencedCodeBlock codeBlock) {
            blocks.add(new DocBlock.Code(codeBlock.getInfo(), codeBlock.getLiteral()));
        }

        @Override
        public void visit(IndentedCodeBlock codeBlock) {
            blocks.add(new DocBlock.Code("", codeBlock.getLiteral()));
        }

        @Override
        public void visit(BulletList bulletList) {
            addList(bulletList, false);
        }

        @Override
        public void visit(OrderedList orderedList) {
            addList(orderedList, true);
        }

        @Override
        public void visit(CustomBlock customBlock) {
            if (!(customBlock instanceof TableBlock tableBlock)) {
                visitChildren(customBlock);
                return;
            }
            List<List<String>> rows = new ArrayList<>();
            for (Node section = tableBlock.getFirstChild(); section != null; section = section.getNext()) {
                for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                    if (row instanceof TableRow tableRow) {
                        List<String> cells = new ArrayList<>();
                        for (Node cell = tableRow.getFirstChild(); cell != null; cell = cell.getNext()) {
                            if (cell instanceof TableCell tableCell) {
                                cells.add(inlineText(tableCell));
                            }
                        }
                        if (!cells.isEmpty()) {
                            rows.add(cells);
                        }
                    }
                }
            }
            if (!rows.isEmpty()) {
                List<String> header = rows.get(0);
                List<List<String>> body = rows.size() > 1 ? rows.subList(1, rows.size()) : List.of();
                blocks.add(new DocBlock.Table(header, body));
            }
        }

        @Override
        public void visit(HtmlBlock htmlBlock) {
            String text = htmlToText(htmlBlock.getLiteral());
            if (!text.isBlank()) {
                blocks.add(new DocBlock.Paragraph(text));
            }
        }

        private void addList(Node listNode, boolean ordered) {
            List<String> items = new ArrayList<>();
            for (Node child = listNode.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof ListItem listItem) {
                    String text = listItemText(listItem);
                    if (!text.isBlank()) {
                        items.add(text);
                    }
                }
            }
            if (!items.isEmpty()) {
                blocks.add(new DocBlock.ListBlock(ordered, items));
            }
        }

        private String listItemText(ListItem listItem) {
            StringBuilder builder = new StringBuilder();
            for (Node child = listItem.getFirstChild(); child != null; child = child.getNext()) {
                appendListItemChild(child, builder);
            }
            return builder.toString().strip();
        }

        private void appendListItemChild(Node node, StringBuilder builder) {
            if (node instanceof BulletList || node instanceof OrderedList) {
                for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                    if (child instanceof ListItem nestedItem) {
                        String nested = listItemText(nestedItem);
                        if (!nested.isBlank()) {
                            builder.append('\n').append("  - ").append(nested.replace("\n", "\n    "));
                        }
                    }
                }
                return;
            }
            if (node instanceof FencedCodeBlock codeBlock) {
                builder.append('\n').append(new DocBlock.Code(codeBlock.getInfo(), codeBlock.getLiteral()).toMarkdown());
                return;
            }
            String text = inlineText(node);
            if (!text.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
    }

    private static String inlineText(Node node) {
        StringBuilder builder = new StringBuilder();
        appendInline(node, builder);
        return builder.toString().replaceAll("[ \\t]+\\n", "\\n").strip();
    }

    /**
     * README 开头的 banner / badge 常是整块 HTML，直接当正文会把标签写进向量，
     * 这里只保留标签内文字；纯图片块（img 没有文本子节点）自然被丢弃。
     */
    private static String htmlToText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return html.replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?s)<!--.*?-->", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|li|tr|h[1-6]|blockquote|section|article)>", "\n")
                .replaceAll("(?i)</(td|th)>", " ")
                .replaceAll("(?s)<[^>]+>", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{2,}", "\n")
                .strip();
    }

    private static void appendInline(Node node, StringBuilder builder) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text text) {
                builder.append(text.getLiteral());
            } else if (child instanceof Code code) {
                builder.append('`').append(code.getLiteral()).append('`');
            } else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) {
                builder.append('\n');
            } else if (child instanceof Image) {
                // README 中的图片暂无多模态处理，避免把 alt 文本当成正文噪声
            } else {
                appendInline(child, builder);
            }
        }
    }
}
