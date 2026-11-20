package com.learnloop.question;

public record  QuestionResponse (
        long id,
        String content,
        String optionA,
        String optionB,
        String optionC,
        String optionD
){
}
//record 是 Java 保存数据的一种简洁写法，会自动生成构造方法和属性访问方法。