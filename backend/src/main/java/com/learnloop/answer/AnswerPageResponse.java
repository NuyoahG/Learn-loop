package com.learnloop.answer; // 声明当前类型所属的包

import java.util.List; // 导入列表类型

public record AnswerPageResponse( // 定义分页接口返回的数据结构
                                  int page, // 当前页码，从 1 开始
                                  int size, // 每页最多返回多少条
                                  long total, // 数据库中的记录总数
                                  long totalPages, // 按当前 size 计算出的总页数
                                  List<AnswerRecordResponse> records // 当前这一页的答题记录
) { // record 主体开始
} // 类型定义结束
//AnswerRecordResponse：一条答题记录。
//AnswerPageResponse：一页答题记录，以及分页信息