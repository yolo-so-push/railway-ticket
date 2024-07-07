package org.opengoofy.index12306.framework.starter.convention.page;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> implements Serializable {

    private static final long serialVersionUID=1L;

    /**
     * 当前页
     */
    private Long current;

    /**
     * 每页显示条数
     */
    private Long size=10L;

    /**
     * 总数
     */
    private Long total;

    /**
     * 结果数据列表
     */
    private List<T> records= Collections.emptyList();

    public PageResponse setRecords(List<T> records){
        this.records=records;
        return this;
    }
    public <R> PageResponse<R> convert(Function<?super T,? extends R> mapper){
        List<R> collect = this.records.stream().map(mapper).collect(Collectors.toList());
        return ((PageResponse<R>) this).setRecords(collect);
    }
}
