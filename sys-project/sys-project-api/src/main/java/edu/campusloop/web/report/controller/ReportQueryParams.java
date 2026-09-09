package edu.campusloop.web.report.controller;

import edu.campusloop.common.ApiException;
import org.springframework.util.MultiValueMap;
import java.util.Set;

final class ReportQueryParams {
    private ReportQueryParams() {}
    static void allowed(MultiValueMap<String,String> params,Set<String> names) {
        if(params.entrySet().stream().anyMatch(entry->!names.contains(entry.getKey()) || entry.getValue().size()!=1))
            throw new ApiException(400,"存在不支持或重复的查询参数");
    }
    static int number(MultiValueMap<String,String> params,String name,int fallback) {
        String raw=params.getFirst(name);if(raw==null) return fallback;
        if(!raw.matches("[0-9]{1,10}")) throw new ApiException(400,"分页参数须为整数");
        try{return Integer.parseInt(raw);}catch(NumberFormatException bad){throw new ApiException(400,"分页参数超出范围");}
    }
}
