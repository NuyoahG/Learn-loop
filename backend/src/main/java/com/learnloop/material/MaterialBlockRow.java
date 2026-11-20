package com.learnloop.material;

/** 内部映射对象；sourceLocator 是数据库 JSON，不直接让浏览器绑定此对象。 */
public class MaterialBlockRow {
    private Long id;
    private Long materialId;
    private Integer blockNo;
    private String blockType;
    private String textContent;
    private String sourceLocator;
    public Long getId() { return id; }
    public void setId(Long value) { id = value; }
    public Long getMaterialId() { return materialId; }
    public void setMaterialId(Long value) { materialId = value; }
    public Integer getBlockNo() { return blockNo; }
    public void setBlockNo(Integer value) { blockNo = value; }
    public String getBlockType() { return blockType; }
    public void setBlockType(String value) { blockType = value; }
    public String getTextContent() { return textContent; }
    public void setTextContent(String value) { textContent = value; }
    public String getSourceLocator() { return sourceLocator; }
    public void setSourceLocator(String value) { sourceLocator = value; }
}
