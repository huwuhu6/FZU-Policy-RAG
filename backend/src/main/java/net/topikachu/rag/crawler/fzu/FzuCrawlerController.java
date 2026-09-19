package net.topikachu.rag.crawler.fzu;

import lombok.RequiredArgsConstructor;
import net.topikachu.rag.common.AjaxResult;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/admin/crawl")
@RequiredArgsConstructor
public class FzuCrawlerController {

    private final FzuCrawlService crawlService;

    @PostMapping("/fzu")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> crawl(Mono<Principal> principalMono) {
        return principalMono.map(Principal::getName)
                .defaultIfEmpty("")
                .flatMap(crawlService::crawl)
                .map(AjaxResult::success);
    }
}
