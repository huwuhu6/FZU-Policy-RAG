package net.topikachu.rag.crawler.fzu;

import net.topikachu.rag.business.document.vo.DocumentSourceMetadata;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Thin, single-site crawler for the Fuzhou University Academic Affairs Office. */
@Component
public class FzuJwcCrawler {

    private static final Pattern DATE_PATTERN = Pattern.compile(
            "(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?");
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("pdf", "doc", "docx");
    private static final List<String> RELEVANT_KEYWORDS = List.of(
            "转专业", "培养方案", "培养计划", "课程替代", "学籍", "休学", "复学", "退学", "转学",
            "选课", "退选", "补选", "重修", "免修", "课程考核", "成绩", "绩点", "缓考", "补考",
            "辅修", "学分认定", "创新创业学分", "竞赛认定", "毕业", "学位", "毕业设计");
    private static final List<String> ATTACHMENT_BLACKLIST = List.of(
            "申请表", "审批表", "报名表", "汇总表", "名单", "模板", "回执", "统计表", "申报书", "承诺书");
    private static final List<String> TITLE_BLACKLIST = List.of("名单");

    private final String baseUrl;
    private final int noticeMaxPages;
    private final int teachingFileMaxPages;
    private final int handbookMaxPages;
    private final int timeoutMs;
    private final String userAgent;
    private final HttpClient httpClient;

    public FzuJwcCrawler(
            @Value("${rag.crawler.fzu.base-url:https://jwch.fzu.edu.cn}") String baseUrl,
            @Value("${rag.crawler.fzu.notice-max-pages:20}") int noticeMaxPages,
            @Value("${rag.crawler.fzu.teaching-file-max-pages:8}") int teachingFileMaxPages,
            @Value("${rag.crawler.fzu.handbook-max-pages:3}") int handbookMaxPages,
            @Value("${rag.crawler.fzu.timeout-ms:15000}") int timeoutMs,
            @Value("${rag.crawler.fzu.user-agent:FZU-Policy-RAG/1.0}") String userAgent) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.noticeMaxPages = noticeMaxPages;
        this.teachingFileMaxPages = teachingFileMaxPages;
        this.handbookMaxPages = handbookMaxPages;
        this.timeoutMs = timeoutMs;
        this.userAgent = userAgent;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CrawlDiscovery discover() throws IOException {
        DiscoveryAccumulator accumulator = new DiscoveryAccumulator();
        Set<String> seenDetailUrls = new HashSet<>();
        crawlSource(accumulator, seenDetailUrls, "/jxwj/xssc/a2025n.htm", SourceSection.STUDENT_HANDBOOK, 2025, handbookMaxPages);
        crawlSource(accumulator, seenDetailUrls, "/jxwj/xssc/a2024n.htm", SourceSection.STUDENT_HANDBOOK, 2024, handbookMaxPages);
        crawlSource(accumulator, seenDetailUrls, "/jxwj/xssc/a2023n.htm", SourceSection.STUDENT_HANDBOOK, 2023, handbookMaxPages);
        crawlSource(accumulator, seenDetailUrls, "/jxwj.htm", SourceSection.TEACHING_FILE, null, teachingFileMaxPages);
        crawlSource(accumulator, seenDetailUrls, "/jxtz.htm", SourceSection.TEACHING_NOTICE, null, noticeMaxPages);
        return accumulator.toDiscovery();
    }

    private void crawlSource(DiscoveryAccumulator accumulator,
                             Set<String> seenDetailUrls,
                             String relativeStart,
                             SourceSection section,
                             Integer handbookYear,
                             int maxPages) {
        String pageUrl = absoluteUrl(relativeStart);
        Set<String> visitedPages = new LinkedHashSet<>();
        for (int page = 0; page < maxPages && pageUrl != null && visitedPages.add(pageUrl); page++) {
            try {
                org.jsoup.nodes.Document listPage = fetchHtml(pageUrl);
                accumulator.pagesScanned++;
                for (ListItem item : parseListPage(listPage, pageUrl, section, handbookYear)) {
                    accumulator.discovered++;
                    if (!isRelevantTitle(item.title())) {
                        accumulator.skipped++;
                        continue;
                    }
                    accumulator.relevant++;
                    if (!seenDetailUrls.add(item.url())) {
                        continue;
                    }
                    try {
                        DetailPage detail = parseDetail(fetchHtml(item.url()), item);
                        accumulator.skipped += detail.skippedAttachments();
                        accumulator.artifacts.addAll(toArtifacts(detail));
                    } catch (Exception error) {
                        accumulator.failures.add(item.url() + " | " + message(error));
                    }
                }
                pageUrl = findNextPageUrl(listPage, pageUrl);
            } catch (Exception error) {
                accumulator.failures.add(pageUrl + " | " + message(error));
                break;
            }
        }
    }

    public List<ListItem> parseListPage(org.jsoup.nodes.Document page,
                                        String pageUrl,
                                        SourceSection section,
                                        Integer handbookYear) {
        List<ListItem> items = new ArrayList<>();
        for (Element li : page.select("ul.list-gl li")) {
            Element link = li.select("a[href]").first();
            if (link == null) {
                continue;
            }
            String url = link.absUrl("href");
            if (url.isBlank()) {
                url = URI.create(pageUrl).resolve(link.attr("href")).toString();
            }
            String title = link.text().trim();
            LocalDate listDate = parseDate(li.text());
            items.add(new ListItem(title, url, listDate, section, handbookYear));
        }
        return items;
    }

    public DetailPage parseDetail(org.jsoup.nodes.Document page, ListItem item) {
        Element main = page.select(".articelMain").first();
        if (main == null) {
            throw new IllegalArgumentException("Detail body .articelMain not found: " + item.url());
        }
        Element titleElement = main.select(".artiTitle, h1").first();
        String title = item.title();
        if (title == null || title.isBlank()) {
            title = titleElement == null || titleElement.text().isBlank()
                    ? "福州大学教务处政策资料"
                    : titleElement.text().trim();
        }
        LocalDate publishDate = parseDate(page.text().replace('\u00a0', ' '));
        String body = normalizedBody(main, title);
        List<Attachment> attachments = new ArrayList<>();
        int skippedAttachments = 0;
        for (Element link : main.select("a[href]")) {
            String url = link.absUrl("href");
            if (url.isBlank()) {
                continue;
            }
            String fileName = fileNameFromUrl(url, link.text());
            if (isSupportedAttachment(fileName)) {
                if (isBlacklistedAttachment(fileName)) {
                    skippedAttachments++;
                } else {
                    attachments.add(new Attachment(fileName, url));
                }
            } else if (hasFileExtension(fileName)) {
                skippedAttachments++;
            }
        }
        DocumentSourceMetadata metadata = new DocumentSourceMetadata(
                item.url(), null, item.section().name(), publishDate, item.handbookYear());
        return new DetailPage(title, item.url(), body, metadata, attachments, skippedAttachments);
    }

    public static boolean isRelevantTitle(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        String normalized = title.replaceAll("\\s+", "");
        if (TITLE_BLACKLIST.stream().anyMatch(normalized::contains)) {
            return false;
        }
        return RELEVANT_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    public static boolean isSupportedAttachment(String fileName) {
        String extension = extension(fileName);
        return SUPPORTED_EXTENSIONS.contains(extension);
    }

    public static boolean isBlacklistedAttachment(String fileName) {
        String normalized = fileName == null ? "" : fileName.replaceAll("\\s+", "");
        return ATTACHMENT_BLACKLIST.stream().anyMatch(normalized::contains);
    }

    public void writeArtifact(CrawlArtifact artifact, Path target) throws IOException {
        if (artifact.inlineContent() != null) {
            Files.writeString(target, artifact.inlineContent(), StandardCharsets.UTF_8);
            return;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(artifact.metadata().artifactUrl()))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent", userAgent)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("HTTP " + response.statusCode());
            }
            Files.write(target, response.body());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted downloading artifact", interrupted);
        }
    }

    private List<CrawlArtifact> toArtifacts(DetailPage detail) {
        List<CrawlArtifact> artifacts = new ArrayList<>();
        String markdown = "# " + detail.title() + "\n\n"
                + "发布时间：" + (detail.metadata().publishDate() == null ? "未知" : detail.metadata().publishDate()) + "\n\n"
                + "来源：" + detail.sourceUrl() + "\n\n"
                + detail.body() + "\n";
        String markdownFileName = safeFileName(detail.title()) + ".md";
        artifacts.add(new CrawlArtifact(markdownFileName, "text/markdown", markdown,
                new DocumentSourceMetadata(detail.sourceUrl(), null, detail.metadata().sourceSection(),
                        detail.metadata().publishDate(), detail.metadata().handbookYear())));
        for (Attachment attachment : detail.attachments()) {
            artifacts.add(new CrawlArtifact(attachment.fileName(), contentType(attachment.fileName()), null,
                    new DocumentSourceMetadata(detail.sourceUrl(), attachment.url(), detail.metadata().sourceSection(),
                            detail.metadata().publishDate(), detail.metadata().handbookYear())));
        }
        return artifacts;
    }

    private org.jsoup.nodes.Document fetchHtml(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent(userAgent)
                .timeout(timeoutMs)
                .followRedirects(true)
                .ignoreHttpErrors(false)
                .maxBodySize(0)
                .get();
    }

    String findNextPageUrl(org.jsoup.nodes.Document page, String pageUrl) {
        for (Element link : page.select("a[href]")) {
            String text = link.text().replaceAll("\\s+", "");
            if (text.contains("下页") || text.contains("下一页")) {
                String next = link.absUrl("href");
                return next.isBlank() ? URI.create(pageUrl).resolve(link.attr("href")).toString() : next;
            }
        }
        return null;
    }

    private String normalizedBody(Element main, String title) {
        Element copy = main.clone();
        copy.select("script,style,nav,header,footer,.share,.artiTitle").remove();
        String body = copy.text().trim();
        if (body.startsWith(title)) {
            body = body.substring(title.length()).trim();
        }
        return body;
    }

    private LocalDate parseDate(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = DATE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String fileNameFromUrl(String url, String anchorText) {
        try {
            if (anchorText != null && hasFileExtension(anchorText.trim())) {
                return anchorText.trim();
            }
            String path = URI.create(url).getPath();
            String last = path == null ? "" : path.substring(path.lastIndexOf('/') + 1);
            String decoded = URLDecoder.decode(last, StandardCharsets.UTF_8);
            return decoded.isBlank() ? anchorText.trim() : decoded;
        } catch (RuntimeException ignored) {
            return anchorText == null ? "attachment" : anchorText.trim();
        }
    }

    private boolean hasFileExtension(String fileName) {
        String ext = extension(fileName);
        return !ext.isBlank();
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int index = fileName.lastIndexOf('.');
        return index < 0 ? "" : fileName.substring(index + 1).toLowerCase(Locale.ROOT);
    }

    private String absoluteUrl(String relative) {
        return URI.create(baseUrl).resolve(relative).toString();
    }

    private String safeFileName(String title) {
        String value = title == null ? "fzu-policy" : title.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_");
        return value.length() > 180 ? value.substring(0, 180) : value;
    }

    private String contentType(String fileName) {
        String extension = extension(fileName);
        return switch (extension) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default -> "application/octet-stream";
        };
    }

    private String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://jwch.fzu.edu.cn";
        }
        return value.replaceAll("/+$", "");
    }

    public enum SourceSection { STUDENT_HANDBOOK, TEACHING_FILE, TEACHING_NOTICE }

    public record ListItem(String title, String url, LocalDate listDate, SourceSection section, Integer handbookYear) {}

    public record Attachment(String fileName, String url) {}

    public record DetailPage(String title,
                              String sourceUrl,
                              String body,
                              DocumentSourceMetadata metadata,
                              List<Attachment> attachments,
                              int skippedAttachments) {}

    public record CrawlArtifact(String fileName, String contentType, String inlineContent,
                                DocumentSourceMetadata metadata) {}

    public record CrawlDiscovery(int pagesScanned,
                                 int discovered,
                                 int relevant,
                                 int skipped,
                                 List<CrawlArtifact> artifacts,
                                 List<String> failures) {}

    private static final class DiscoveryAccumulator {
        private int pagesScanned;
        private int discovered;
        private int relevant;
        private int skipped;
        private final List<CrawlArtifact> artifacts = new ArrayList<>();
        private final List<String> failures = new ArrayList<>();

        private CrawlDiscovery toDiscovery() {
            return new CrawlDiscovery(pagesScanned, discovered, relevant, skipped,
                    List.copyOf(artifacts), List.copyOf(failures));
        }
    }
}
