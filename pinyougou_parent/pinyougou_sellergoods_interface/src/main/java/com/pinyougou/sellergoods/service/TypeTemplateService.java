package com.pinyougou.sellergoods.service;

import java.util.List;
import java.util.Map;

import com.pinyougou.pojo.TbTypeTemplate;

import entity.PageResult;

/**
 * 业务逻辑接口
 *
 * @author Steven
 */
public interface TypeTemplateService {

    /**
     * 返回全部列表
     *
     * @return
     */
    public List<TbTypeTemplate> findAll();


    /**
     * 返回分页列表
     *
     * @return
     */
    public PageResult findPage(int pageNum, int pageSize);


    /**
     * 增加
     */
    public void add(TbTypeTemplate typeTemplate);


    /**
     * 修改
     */
    public void update(TbTypeTemplate typeTemplate);


    /**
     * 根据ID获取实体
     *
     * @param id
     * @return
     */
    public TbTypeTemplate findOne(Long id);


    /**
     * 批量删除
     *
     * @param ids
     */
    public void delete(Long[] ids);

    /**
     * 分页
     *
     * @param pageNum  当前页 码
     * @param pageSize 每页记录数
     * @return
     */
    public PageResult findPage(TbTypeTemplate typeTemplate, int pageNum, int pageSize);

    /**
     * 跟据模板id查询规格与选项列表
     *
     * @param id 模板id
     * @return 规格与选项列表
     */
    public List<Map> findSpecList(Long id);

}
