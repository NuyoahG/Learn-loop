package com.learnloop.material;

import java.util.List;
/** 与现有历史分页保持相同基本结构；超出末页仍返回空 records 和正确的本人总数。 */
public record MaterialPageResponse(int page, int size, long total, long totalPages,
                                   List<MaterialResponse> records) {}
