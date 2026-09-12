package com.dbts.glyahhaigeneratecode.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.dbts.glyahhaigeneratecode.exception.ErrorCode;
import com.dbts.glyahhaigeneratecode.exception.MyException;
import com.dbts.glyahhaigeneratecode.mapper.UserMapper;
import com.dbts.glyahhaigeneratecode.model.DTO.UserLoginRequest;
import com.dbts.glyahhaigeneratecode.model.DTO.UserQueryRequest;
import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.VO.LoginUserVO;
import com.dbts.glyahhaigeneratecode.model.VO.UserVO;
import com.dbts.glyahhaigeneratecode.model.enums.UserRoleEnum;
import com.dbts.glyahhaigeneratecode.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;


import static com.dbts.glyahhaigeneratecode.constant.UserConstant.USER_LOGIN_STATE;

/**
 * 用户 服务层实现。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User>  implements UserService {

    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword) {
        // 1. 校验密码
        if (userPassword.length() < 8 || checkPassword.length() < 8) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "用户密码过短, 至少8位");
        }
        if (!userPassword.equals(checkPassword)) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        }
        if (StrUtil.hasBlank(userAccount, userPassword, checkPassword)){
            throw new MyException(ErrorCode.PARAMS_ERROR, "参数有空");
        }

        // 2. 查询账号是否已存在
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(User::getUserAccount, userAccount);
        long accountAmount = this.mapper.selectCountByQuery(queryWrapper);
        if (accountAmount > 0) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "账号已存在");
        }

        // 3. 密码加密
        String salt = "glyahh";
        String SecretPassword = DigestUtils.md5DigestAsHex((salt + userPassword).getBytes());

        // 4. 插入数据
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(SecretPassword);
        user.setUserRole(UserRoleEnum.USER.getValue());
        user.setUserName("取个名字吧");
        // 使用 insertSelective 忽略 null 字段，让数据库默认值与触发器生效
        int insert = this.mapper.insertSelective(user);
        if (insert < 1) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "用户输入成功,数据库插入出错");
        }

        return user.getId();
    }

    @Override
    public LoginUserVO UserSwitch(User user) {
        if (user == null){
            throw new MyException(ErrorCode.NOT_LOGIN_ERROR, "用户脱敏失败");
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtil.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    @Override
    public LoginUserVO userLogin(UserLoginRequest user, HttpServletRequest request) {
        // 1. 校验
        String userAccount = user.getUserAccount();
        String userPassword = user.getUserPassword();
        if (userPassword.length() < 8) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "用户密码过短, 至少8位");
        }
        if (StrUtil.hasBlank(userAccount, userPassword)){
            throw new MyException(ErrorCode.PARAMS_ERROR, "参数有空");
        }

        // 2. 查询账号
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(User::getUserAccount, userAccount);
        String salt = "glyahh";
        String SecretPassword = DigestUtils.md5DigestAsHex((salt + userPassword).getBytes());
        queryWrapper.eq(User::getUserPassword, SecretPassword);
        User userExist = this.mapper.selectOneByQuery(queryWrapper);

        if (userExist == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "用户不存在, 压根查不到");
        }

        // 3. 脱敏
        LoginUserVO loginUserVO = UserSwitch(userExist);

        // 4. 存入Session
        request.getSession().setAttribute(USER_LOGIN_STATE, userExist);

        return loginUserVO;
    }

    @Override
    public User getUserInSession(HttpServletRequest request) {
        // 1. 从session中获取当前登录的user
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        if (userObj == null){
            throw new MyException(ErrorCode.NOT_LOGIN_ERROR, "未从session中获取userObj");
        }
        User user = (User) userObj;

        // 2. 从数据库查询用户最新数据
        QueryWrapper queryWrapper = new QueryWrapper();
        queryWrapper.eq(User::getId, user.getId());
        User userExist = this.mapper.selectOneByQuery(queryWrapper);

        if (userExist == null){
            throw new MyException(ErrorCode.NOT_LOGIN_ERROR, "未从数据库中获取userExist");
        }
        return userExist;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            throw new MyException(ErrorCode.NOT_LOGIN_ERROR, "传入的User用户信息为空，无法转换为 UserVO");
        }
        UserVO userVO = new UserVO();
        BeanUtil.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (userList == null ) {
            throw new MyException(ErrorCode.NOT_LOGIN_ERROR, "传入的UserList用户信息为空，无法转换为UserVOList");
        }
        if  (userList.isEmpty()){
            return Collections.emptyList();
        }
        return userList.stream()
                //传入一个方法，将User对象转换为UserVO对象
                .map(this::getUserVO)
                .collect(Collectors.toList());
    }

    @Override
    public QueryWrapper buildUserQueryWrapper(UserQueryRequest userQueryRequest) {
        QueryWrapper queryWrapper = new QueryWrapper();
        if (userQueryRequest == null) {
            throw new MyException(ErrorCode.PARAMS_ERROR, "转化QueryWrapper时,请求参数userQueryRequest为空");
        }

        Long id = userQueryRequest.getId();
        String userName = userQueryRequest.getUserName();
        String userAccount = userQueryRequest.getUserAccount();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();

        if (id != null) {
            queryWrapper.eq(User::getId, id);
        }
        if (StrUtil.isNotBlank(userName)) {
            queryWrapper.like(User::getUserName, userName);
        }
        if (StrUtil.isNotBlank(userAccount)) {
            queryWrapper.eq(User::getUserAccount, userAccount);
        }
        if (StrUtil.isNotBlank(userProfile)) {
            queryWrapper.like(User::getUserProfile, userProfile);
        }
        if (StrUtil.isNotBlank(userRole)) {
            queryWrapper.eq(User::getUserRole, userRole);
        }
        if (StrUtil.isNotBlank(sortField) && StrUtil.isNotBlank(sortOrder)) {
            queryWrapper.orderBy("createTime".equals(sortField) ? "create_time" : sortField,
                    "ascend".equals(sortOrder));
        }


        return queryWrapper;
    }

    @Override
    public String getEncryptPassword(String Password) {
        String salt = "glyahh";
        return DigestUtils.md5DigestAsHex((salt + Password).getBytes());
    }

    @Override
    public boolean userLogout(HttpServletRequest request) {
        // 1. 先判断是否已登录
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        if (userObj == null) {
            throw new MyException(ErrorCode.OPERATION_ERROR, "用户还未登录呢,干嘛删掉");
        }

        // 2. 移除登录态
        request.getSession().removeAttribute(USER_LOGIN_STATE);

        return true;
    }

}
