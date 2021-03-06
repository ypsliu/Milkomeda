package com.github.yizzuide.milkomeda.particle;

import com.github.yizzuide.milkomeda.universe.metadata.BeanIds;
import com.github.yizzuide.milkomeda.universe.parser.url.URLPathMatcher;
import com.github.yizzuide.milkomeda.universe.parser.url.URLPlaceholderParser;
import com.github.yizzuide.milkomeda.universe.parser.url.URLPlaceholderResolver;
import com.github.yizzuide.milkomeda.universe.parser.yml.YmlResponseOutput;
import com.github.yizzuide.milkomeda.util.JSONUtil;
import lombok.SneakyThrows;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ParticleFilter
 * 限制器过滤器
 *
 * @author yizzuide
 * @since 3.0.0
 * @version 3.1.2
 * Create at 2020/04/08 11:41
 */
public class ParticleFilter implements Filter {

    @Autowired
    private ParticleProperties particleProperties;

    @Autowired(required = false) @Qualifier(BeanIds.PARTICLE_RESOLVER)
    private URLPlaceholderResolver particleURLPlaceholderResolver;

    // 占位解析器
    private URLPlaceholderParser urlPlaceholderParser;

    // 跳过拦截
    private boolean skip = false;

    @PostConstruct
    public void init() {
        List<ParticleProperties.Limiter> limiters = particleProperties.getLimiters();
        if (CollectionUtils.isEmpty(limiters)) {
            skip = true;
            return;
        }
        urlPlaceholderParser = new URLPlaceholderParser();
        urlPlaceholderParser.setCustomURLPlaceholderResolver(particleURLPlaceholderResolver);
        for (ParticleProperties.Limiter limiter : limiters) {
            if (StringUtils.isEmpty(limiter.getKeyTpl())) {
                continue;
            }
            limiter.setCacheKeys(urlPlaceholderParser.grabPlaceHolders(limiter.getKeyTpl()));
        }
    }

    @SneakyThrows
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        if (skip || (!CollectionUtils.isEmpty(particleProperties.getExcludeUrls()) && URLPathMatcher.match(particleProperties.getExcludeUrls(), httpServletRequest.getRequestURI())) ||
                (!CollectionUtils.isEmpty(particleProperties.getIncludeUrls()) && !URLPathMatcher.match(particleProperties.getIncludeUrls(), httpServletRequest.getRequestURI()))) {
            chain.doFilter(request, response);
            return;
        }

        boolean matchFlag = false;
        List<ParticleProperties.Limiter> urlLimiters = particleProperties.getLimiters();
        for (ParticleProperties.Limiter limiter : urlLimiters) {
            // 忽略需排除的URL
            if (!CollectionUtils.isEmpty(limiter.getExcludeUrls()) && URLPathMatcher.match(limiter.getExcludeUrls(), httpServletRequest.getRequestURI())) {
                continue;
            }
            if (CollectionUtils.isEmpty(limiter.getIncludeUrls())) {
                continue;
            }
            if (!URLPathMatcher.match(limiter.getIncludeUrls(), httpServletRequest.getRequestURI())) {
                continue;
            }
            // 设置匹配标识
            matchFlag = true;
            String key = urlPlaceholderParser.parse(limiter.getKeyTpl(), httpServletRequest, null, limiter.getCacheKeys());
            Map<String, Object> returnData = limiter.getLimitHandler().limit(key, limiter.getKeyExpire().getSeconds(), particle -> {
                if (particle.isLimited()) {
                    Map<String, Object> result = new HashMap<>(8);
                    Map<String, Object> responseInfo = limiter.getResponse();
                    // 查找全局的响应
                    if (responseInfo == null) {
                        responseInfo = particleProperties.getResponse();
                    }
                    // 如果没有响应，返回默认响应码
                    if (responseInfo == null || responseInfo.get(YmlResponseOutput.STATUS) == null) {
                        result.put(YmlResponseOutput.STATUS, 416);
                        return result;
                    }
                    int status = Integer.parseInt(responseInfo.get(YmlResponseOutput.STATUS).toString());
                    result.put(YmlResponseOutput.STATUS, status);
                    YmlResponseOutput.output(responseInfo, result, null, null, false);
                    return result;
                }
                chain.doFilter(request, response);
                return null;
            });

            if (returnData != null) {
                HttpServletResponse httpServletResponse = (HttpServletResponse) response;
                httpServletResponse.setStatus(Integer.parseInt(returnData.get(YmlResponseOutput.STATUS).toString()));
                returnData.remove(YmlResponseOutput.STATUS);
                httpServletResponse.setCharacterEncoding("UTF-8");
                httpServletResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
                PrintWriter writer = httpServletResponse.getWriter();
                writer.println(JSONUtil.serialize(returnData));
                writer.flush();
                return;
            }

            // 只支持一个限制器，可通过排序来确定，限制器链可以使用Barrier类型
            break;
        }

        // 放过不需要限制的请求
        if (!matchFlag) {
            chain.doFilter(request, response);
        }
    }
}
