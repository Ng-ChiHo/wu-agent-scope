package com.chiho.wuagentscope.service;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chiho.wuagentscope.common.exception.BusinessException;
import com.chiho.wuagentscope.common.exception.ErrorCode;
import com.chiho.wuagentscope.entity.UserDO;
import com.chiho.wuagentscope.mapper.UserMapper;
import com.chiho.wuagentscope.model.LoginRequest;
import com.chiho.wuagentscope.model.LoginResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户服务
 * <p>
 * 基于 MySQL + MyBatis-Plus 实现用户注册、登录、登出、Token 管理。
 * Token 存储在 ConcurrentHashMap 中（单机方案），生产环境建议替换为 JWT + Redis。
 * @author ChiHo
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserMapper userMapper;

    /**
     * token -> userId 映射（单机方案，生产环境建议替换为 Redis）
     */
    private final ConcurrentHashMap<String, Long> tokenStore = new ConcurrentHashMap<>();

    /**
     * 用户注册
     *
     * @param username 用户名（唯一）
     * @param password 密码（BCrypt 加密存储）
     * @param nickname 昵称（可选，默认等于用户名）
     * @return 注册成功的用户信息
     */
    @Transactional(rollbackFor = Exception.class)
    public UserDO register(String username, String password, String nickname) {
        // 检查用户名是否已存在
        LambdaQueryWrapper<UserDO> queryWrapper = new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getUsername, username);
        if (userMapper.selectCount(queryWrapper) > 0) {
            throw new BusinessException(ErrorCode.USER_NAME_UNIQUE);
        }

        // 创建用户（密码 BCrypt 加密）
        UserDO user = new UserDO();
        user.setUsername(username);
        user.setPassword(BCrypt.hashpw(password, BCrypt.gensalt()));
        user.setNickname(nickname != null ? nickname : username);
        user.setStatus(1);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);

        log.info("用户注册成功: {}", username);
        return user;
    }

    /**
     * 用户登录
     *
     * @param request 登录请求（用户名 + 密码）
     * @return 登录响应（含 token）
     */
    public LoginResponse login(LoginRequest request) {
        // 查询用户
        LambdaQueryWrapper<UserDO> queryWrapper = new LambdaQueryWrapper<UserDO>()
                .eq(UserDO::getUsername, request.getUsername());
        UserDO user = userMapper.selectOne(queryWrapper);

        if (user == null) {
            throw new BusinessException(ErrorCode.USER_QUERY_NOT_FOUND);
        }
        if (user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.USER_INVALID_STATUS);
        }

        // 验证密码（BCrypt）
        if (!BCrypt.checkpw(request.getPassword(), user.getPassword())) {
            throw new BusinessException(ErrorCode.PASSWORD_ERROR);
        }

        // 生成 token 并缓存
        String token = IdUtil.fastSimpleUUID();
        tokenStore.put(token, user.getId());

        log.info("用户登录成功: {}", request.getUsername());
        return new LoginResponse(user.getId(), user.getUsername(), user.getNickname(), token);
    }

    /**
     * 验证 token 并获取用户 ID
     *
     * @param token 登录 token
     * @return 用户ID，无效返回 null
     */
    public Long getUserIdByToken(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        return tokenStore.get(token);
    }

    /**
     * 用户登出
     *
     * @param token 登录 token
     */
    public void logout(String token) {
        tokenStore.remove(token);
        log.info("用户登出成功");
    }

    /**
     * 根据用户ID获取用户信息
     */
    public UserDO getUserById(Long userId) {
        return userMapper.selectById(userId);
    }
}
