package com.yuyu.salmonmind.websearch.application.port;

import com.yuyu.salmonmind.websearch.api.WebSearchService.WebSearchReason;

/** Provider Adapter 向应用层传递的无原始响应异常。 */
public class WebSearchProviderException extends RuntimeException {

    private final WebSearchReason reason;

    public WebSearchProviderException(WebSearchReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public WebSearchProviderException(WebSearchReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public WebSearchReason reason() {
        return reason;
    }
}
