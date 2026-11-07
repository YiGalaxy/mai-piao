package com.maipiao.user.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.maipiao.common.core.exception.BizException;
import com.maipiao.common.core.result.ErrorCode;
import com.maipiao.common.core.util.JwtUtil;
import com.maipiao.user.dto.UserDtos;
import com.maipiao.user.entity.User;
import com.maipiao.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserServiceTest {

    private UserMapper userMapper;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;
    private TokenBlacklistService tokenBlacklistService;
    private UserService service;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtUtil = mock(JwtUtil.class);
        tokenBlacklistService = mock(TokenBlacklistService.class);
        service = new UserService(userMapper, passwordEncoder, jwtUtil, tokenBlacklistService);
    }

    @Test
    void registerRejectsExistingPhoneBeforeWriting() {
        when(userMapper.selectCount(any())).thenReturn(1L);
        UserDtos.RegisterRequest request =
                new UserDtos.RegisterRequest("13800000001", "secret1", null);

        BizException exception = assertThrows(BizException.class, () -> service.register(request));

        assertEquals(ErrorCode.USER_PHONE_EXISTS.getCode(), exception.getCode());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void loginReturnsSameErrorWhenUserDoesNotExist() {
        when(userMapper.selectOne(any())).thenReturn(null);
        UserDtos.LoginRequest request = new UserDtos.LoginRequest("13800000001", "secret1");

        BizException exception = assertThrows(BizException.class, () -> service.login(request));

        assertEquals(ErrorCode.USER_PASSWORD_WRONG.getCode(), exception.getCode());
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void loginRejectsDisabledAccountBeforeIssuingToken() {
        User user = new User();
        user.setPhone("13800000001");
        user.setPassword("encoded");
        user.setStatus(0);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("secret1", "encoded")).thenReturn(true);

        BizException exception =
                assertThrows(
                        BizException.class,
                        () -> service.login(new UserDtos.LoginRequest("13800000001", "secret1")));

        assertEquals(ErrorCode.USER_DISABLED.getCode(), exception.getCode());
        verify(jwtUtil, never()).generateUserToken(any(), any());
    }

    @Test
    void loginIssuesAdminTokenForAdminAccount() {
        User user = new User();
        user.setId(7L);
        user.setPhone("13800000000");
        user.setNickname("admin");
        user.setPassword("encoded");
        user.setStatus(1);
        user.setRole(JwtUtil.ROLE_ADMIN);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("secret1", "encoded")).thenReturn(true);
        when(jwtUtil.generateAdminToken(7L, "13800000000")).thenReturn("admin-token");

        UserDtos.LoginResponse response =
                service.login(new UserDtos.LoginRequest("13800000000", "secret1"));

        assertEquals("admin-token", response.token());
        assertEquals(7L, response.userId());
        verify(jwtUtil).generateAdminToken(7L, "13800000000");
    }
}
