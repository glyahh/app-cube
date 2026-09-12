package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.constant.AppConstant;
import com.dbts.glyahhaigeneratecode.constant.UserConstant;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.exception.ThrowUtils;
import com.dbts.glyahhaigeneratecode.mapper.UserAppApplyMapper;
import com.dbts.glyahhaigeneratecode.model.Entity.App;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.Entity.UserAppApply;
import com.dbts.glyahhaigeneratecode.model.VO.ApplyHistoryVO;
import com.dbts.glyahhaigeneratecode.model.VO.ApplyVO;
import com.dbts.glyahhaigeneratecode.service.AppService;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.dbts.glyahhaigeneratecode.service.UserAppApplyService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 用户应用 / 权限申请记录 服务实现。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Service
public class UserAppApplyServiceImpl extends ServiceImpl<UserAppApplyMapper, UserAppApply> implements UserAppApplyService {

    @Resource
    private AppService appService;

    @Resource
    private UserService userService;

    @Override
    public boolean createUserAppApply(Long appId, Integer appPropriety, Integer operate, String applyReason, User loginUser) {
        // 权限校验：如果用户已经是管理员，直接返回 false，由上层返回提示
        if (UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole())) {
            ThrowUtils.throwIf(true, ErrorCode.NO_AUTH_ERROR, "用户已经是管理员，不能重复申请");
        }

        // 操作类型校验
        if (operate == null || (operate != 1 && operate != 2)) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "不支持的申请类型");
        }

        // 如果是申请精选应用，需要校验 appId 合法且是自己的应用，并且应用当前不是精选
        App app = null;
        if (operate == 1) {
            if (appId == null || appId <= 0) {
                throw new MyException(ErrorCode.PARAMS_ERROR, "申请精选应用时，appId 不能为空");
            }
            app = appService.getById(appId);
            if (app == null) {
                throw new MyException(ErrorCode.NOT_FOUND_ERROR, "申请的应用不存在");
            }
            if (!loginUser.getId().equals(app.getUserId())) {
                throw new MyException(ErrorCode.NO_AUTH_ERROR, "只能为自己的应用申请精选");
            }
            // 若应用已是精选（priority = GOOD_APP_PRIORITY），不允许重复申请
            if (app.getPriority() != null && Objects.equals(app.getPriority(), AppConstant.GOOD_APP_PRIORITY)) {
                throw new MyException(ErrorCode.OPERATION_ERROR, "该应用已是精选应用，请勿重复申请");
            }
        }

        // 可选：防止重复提交同一类型的未处理申请
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(UserAppApply::getUserId, loginUser.getId());
        queryWrapper.eq(UserAppApply::getOperate, operate);
        queryWrapper.eq(UserAppApply::getStatus, 0);
        if (operate == 1) {
            queryWrapper.eq(UserAppApply::getAppId, app.getId());
        }
        long count = this.count(queryWrapper);
        if (count > 0) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "已有相同类型的待处理申请，请勿重复提交");
        }

        // 组装申请记录
        UserAppApply apply = new UserAppApply();
        apply.setUserId(loginUser.getId());
        apply.setAppId(operate == 1 ? app.getId() : null);
        apply.setAppPropriety(operate == 1 ? appPropriety : null);
        apply.setOperate(operate);
        apply.setApplyReason(applyReason);
        apply.setStatus(0); // 0-待处理（仍然保留记录，便于管理员查看）
        apply.setCreateTime(LocalDateTime.now());
        apply.setUpdateTime(LocalDateTime.now());
        apply.setIsDelete(0);

        boolean saved = this.save(apply);
        if (!saved) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "创建申请记录失败");
        }

        // 同意 / 同步更新逻辑移动到管理员专用接口 agreeApply 中
        return true;
    }

    @Override
    public List<ApplyVO> listPendingApplyVO(User loginUser) {
        // 1. 权限校验：必须是管理员
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()), ErrorCode.NO_AUTH_ERROR, "仅管理员可查看申请列表");

        // 2. 批量查询所有待处理申请（status=0）
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(UserAppApply::getStatus, 0);
        queryWrapper.orderBy("create_time", false);
        List<UserAppApply> applyList = this.list(queryWrapper);
        if (applyList == null || applyList.isEmpty()) {
            return Collections.emptyList();
        }

        // 4. 批量补齐用户头像
        List<Long> userIds = applyList.stream()
                .map(UserAppApply::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, User> userIdToUser = userIds.isEmpty()
                ? Collections.emptyMap()
                : userService.listByIds(userIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(User::getId, Function.identity(), (oldV, newV) -> oldV));

        // 5. 封装返回 ApplyVO
        return applyList.stream()
                .filter(Objects::nonNull)
                .map(item -> {
                    ApplyVO vo = new ApplyVO();
                    vo.setApplyId(item.getId());
                    vo.setUserId(item.getUserId());
                    User applyUser = userIdToUser.get(item.getUserId());
                    vo.setUserAvatar(applyUser == null ? null : applyUser.getUserAvatar());
                    vo.setOperate(item.getOperate());
                    vo.setReason(item.getApplyReason());
                    // operate=2 时不回传 appId
                    vo.setAppId(item.getOperate() != null && item.getOperate() == 1 ? item.getAppId() : null);
                    return vo;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<ApplyHistoryVO> listMyApplyHistoryVO(User loginUser) {
        // 1. 权限校验：必须是普通用户
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()), ErrorCode.NO_AUTH_ERROR, "仅普通用户可查看申请历史");

        // 2. 查询当前用户的全部申请记录
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(UserAppApply::getUserId, loginUser.getId());
        queryWrapper.orderBy("create_time", false);
        List<UserAppApply> applyList = this.list(queryWrapper);
        if (applyList == null || applyList.isEmpty()) {
            return Collections.emptyList();
        }

        // 3. 批量补齐应用名称（仅 operate=1 且 appId 非空）
        Set<Long> appIds = applyList.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getOperate() != null && item.getOperate() == 1)
                .map(UserAppApply::getAppId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, App> appIdToApp = appIds.isEmpty()
                ? Collections.emptyMap()
                : appService.listByIds(appIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(App::getId, Function.identity(), (oldV, newV) -> oldV));

        // 4. 封装返回 ApplyHistoryVO
        return applyList.stream()
                .filter(Objects::nonNull)
                .map(item -> {
                    ApplyHistoryVO vo = new ApplyHistoryVO();
                    vo.setApplyId(item.getId() == null ? null : String.valueOf(item.getId()));
                    vo.setOperate(item.getOperate());
                    vo.setAppId(item.getAppId() == null ? null : String.valueOf(item.getAppId()));
                    App app = item.getAppId() == null ? null : appIdToApp.get(item.getAppId());
                    vo.setAppName(app == null ? null : app.getAppName());
                    vo.setAppPropriety(item.getAppPropriety());
                    vo.setApplyReason(item.getApplyReason());
                    vo.setStatus(item.getStatus());
                    vo.setReviewRemark(item.getReviewRemark());
                    vo.setCreateTime(item.getCreateTime());
                    vo.setReviewTime(item.getReviewTime());
                    return vo;
                })
                .collect(Collectors.toList());
    }

    @Override
    public boolean agreeApply(Long applyId, User loginUser) {
        // 1. 权限校验：必须是管理员
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()), ErrorCode.NO_AUTH_ERROR, "仅管理员可操作申请");
        ThrowUtils.throwIf(applyId == null || applyId <= 0, ErrorCode.PARAMS_ERROR, "申请 id 异常");

        // 2. 根据 applyId 获取待处理申请
        UserAppApply apply = this.getById(applyId);
        if (apply == null || !Objects.equals(apply.getStatus(), 0)) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "待处理申请不存在或已被处理");
        }

        Integer operate = apply.getOperate();
        if (operate == null || (operate != 1 && operate != 2)) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "申请类型非法");
        }

        // 3. 根据 operate 同步更新业务表
        if (operate == 1) {
            // 应用精选：更新 app.priority
            Long appId = apply.getAppId();
            ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "申请记录缺少应用信息");
            App app = appService.getById(appId);
            if (app == null) {
                throw new MyException(ErrorCode.NOT_FOUND_ERROR, "申请关联的应用不存在");
            }
            int targetPriority = apply.getAppPropriety() != null ? apply.getAppPropriety() : 99;
            app.setPriority(targetPriority);
            app.setUpdateTime(LocalDateTime.now());
            if (!appService.updateById(app)) {
                throw new MyException(ErrorCode.OPERATION_ERROR, "更新应用优先级失败");
            }
        } else if (operate == 2) {
            // 修改管理员：更新 user.userRole = admin
            Long userId = apply.getUserId();
            ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "申请记录缺少用户信息");
            User user = userService.getById(userId);
            if (user == null) {
                throw new MyException(ErrorCode.NOT_FOUND_ERROR, "申请关联的用户不存在");
            }
            user.setUserRole(UserConstant.ADMIN_ROLE);
            user.setUpdateTime(LocalDateTime.now());
            if (!userService.updateById(user)) {
                throw new MyException(ErrorCode.OPERATION_ERROR, "更新用户角色失败");
            }
        }

        // 4. 更新申请记录状态为已通过
        apply.setStatus(1);
        apply.setReviewUserId(loginUser.getId());
        apply.setReviewTime(LocalDateTime.now());
        apply.setUpdateTime(LocalDateTime.now());
        if (!this.updateById(apply)) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "更新申请状态失败");
        }

        return true;
    }

    @Override
    public boolean rejectApply(Long applyId, String reviewRemark, String applyReason, User loginUser) {
        // 1. 权限校验：必须是管理员，且申请用户为普通用户
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);
        ThrowUtils.throwIf(!UserConstant.ADMIN_ROLE.equals(loginUser.getUserRole()), ErrorCode.NO_AUTH_ERROR, "仅管理员可操作申请");
        ThrowUtils.throwIf(applyId == null || applyId <= 0, ErrorCode.PARAMS_ERROR, "申请 id 异常");
        if (StrUtil.isBlank(reviewRemark)) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "审核备注不能为空");
        }

        // 2. 根据 applyId 获取待处理申请
        UserAppApply apply = this.getById(applyId);
        if (apply == null || !Objects.equals(apply.getStatus(), 0)) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "待处理申请不存在或已被处理");
        }

        // 校验申请用户为普通用户
        Long applyUserId = apply.getUserId();
        ThrowUtils.throwIf(applyUserId == null || applyUserId <= 0, ErrorCode.PARAMS_ERROR, "申请记录缺少用户信息");
        User applyUser = userService.getById(applyUserId);
        if (applyUser == null) {
            throw new MyException(ErrorCode.NOT_FOUND_ERROR, "申请关联的用户不存在");
        }
        ThrowUtils.throwIf(!UserConstant.DEFAULT_ROLE.equals(applyUser.getUserRole()),
                ErrorCode.NO_AUTH_ERROR, "仅普通用户的申请可被拒绝处理");

        // 3. 更新申请记录状态为已拒绝，并记录审核备注；如果管理员传入了新的申请理由，则一并覆盖
        apply.setStatus(2);
        apply.setReviewUserId(loginUser.getId());
        apply.setReviewRemark(reviewRemark);
        if (StrUtil.isNotBlank(applyReason)) {
            apply.setApplyReason(applyReason);
        }
        apply.setReviewTime(LocalDateTime.now());
        apply.setUpdateTime(LocalDateTime.now());
        if (!this.updateById(apply)) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "更新申请状态失败");
        }

        return true;
    }
}
