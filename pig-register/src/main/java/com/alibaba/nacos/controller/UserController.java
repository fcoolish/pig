/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.controller;

import com.alibaba.nacos.api.common.Constants;
import com.alibaba.nacos.auth.annotation.Secured;
import com.alibaba.nacos.auth.common.ActionTypes;
import com.alibaba.nacos.auth.common.AuthConfigs;
import com.alibaba.nacos.auth.common.AuthSystemTypes;
import com.alibaba.nacos.auth.exception.AccessException;
import com.alibaba.nacos.common.model.RestResult;
import com.alibaba.nacos.common.utils.JacksonUtils;
import com.alibaba.nacos.common.utils.Objects;
import com.alibaba.nacos.config.server.auth.RoleInfo;
import com.alibaba.nacos.config.server.model.User;
import com.alibaba.nacos.config.server.utils.RequestUtil;
import com.alibaba.nacos.security.nacos.JwtTokenManager;
import com.alibaba.nacos.security.nacos.NacosAuthConfig;
import com.alibaba.nacos.security.nacos.NacosAuthManager;
import com.alibaba.nacos.security.nacos.roles.NacosRoleServiceImpl;
import com.alibaba.nacos.security.nacos.users.NacosUser;
import com.alibaba.nacos.security.nacos.users.NacosUserDetailsServiceImpl;
import com.alibaba.nacos.utils.PasswordEncoderUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * User related methods entry.
 *
 * @author wfnuser
 * @author nkorange
 */
@RestController("user")
@RequestMapping({ "/v1/auth", "/v1/auth/users" })
public class UserController {

	@Autowired
	private JwtTokenManager jwtTokenManager;

	@Autowired
	private AuthenticationManager authenticationManager;

	@Autowired
	private NacosUserDetailsServiceImpl userDetailsService;

	@Autowired
	private NacosRoleServiceImpl roleService;

	@Autowired
	private AuthConfigs authConfigs;

	@Autowired
	private NacosAuthManager authManager;

	/**
	 * Create a new user.
	 * @param username username
	 * @param password password
	 * @return ok if create succeed
	 * @throws IllegalArgumentException if user already exist
	 * @since 1.2.0
	 */
	@Secured(resource = NacosAuthConfig.CONSOLE_RESOURCE_NAME_PREFIX + "users", action = ActionTypes.WRITE)
	@PostMapping
	public Object createUser(@RequestParam String username, @RequestParam String password) {

		User user = userDetailsService.getUserFromDatabase(username);
		if (user != null) {
			throw new IllegalArgumentException("user '" + username + "' already exist!");
		}
		userDetailsService.createUser(username, PasswordEncoderUtil.encode(password));
		return new RestResult<>(200, "create user ok!");
	}

	/**
	 * Delete an existed user.
	 * @param username username of user
	 * @return ok if deleted succeed, keep silent if user not exist
	 * @since 1.2.0
	 */
	@DeleteMapping
	@Secured(resource = NacosAuthConfig.CONSOLE_RESOURCE_NAME_PREFIX + "users", action = ActionTypes.WRITE)
	public Object deleteUser(@RequestParam String username) {
		List<RoleInfo> roleInfoList = roleService.getRoles(username);
		if (roleInfoList != null) {
			for (RoleInfo roleInfo : roleInfoList) {
				if (roleInfo.getRole().equals(NacosRoleServiceImpl.GLOBAL_ADMIN_ROLE)) {
					throw new IllegalArgumentException("cannot delete admin: " + username);
				}
			}
		}
		userDetailsService.deleteUser(username);
		return new RestResult<>(200, "delete user ok!");
	}

	/**
	 * Update an user.
	 * @param username username of user
	 * @param newPassword new password of user
	 * @param response http response
	 * @param request http request
	 * @return ok if update succeed
	 * @throws IllegalArgumentException if user not exist or oldPassword is incorrect
	 * @since 1.2.0
	 */
	@PutMapping
	@Secured(resource = NacosAuthConfig.UPDATE_PASSWORD_ENTRY_POINT, action = ActionTypes.WRITE)
	public Object updateUser(@RequestParam String username, @RequestParam String newPassword,
			HttpServletResponse response, HttpServletRequest request) throws IOException {
		// admin or same user
		if (!hasPermission(username, request)) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "authorization failed!");
		}

		User user = userDetailsService.getUserFromDatabase(username);
		if (user == null) {
			throw new IllegalArgumentException("user " + username + " not exist!");
		}

		userDetailsService.updateUserPassword(username, PasswordEncoderUtil.encode(newPassword));

		return new RestResult<>(200, "update user ok!");
	}

	private boolean hasPermission(String username, HttpServletRequest request) {
		if (!authConfigs.isAuthEnabled()) {
			return true;
		}
		if (Objects.isNull(request.getAttribute(RequestUtil.NACOS_USER_KEY))) {
			return false;
		}

		NacosUser user = (NacosUser) request.getAttribute(RequestUtil.NACOS_USER_KEY);
		// admin
		if (user.isGlobalAdmin()) {
			return true;
		}
		// same user
		return user.getUserName().equals(username);
	}

	/**
	 * Get paged users.
	 * @param pageNo number index of page
	 * @param pageSize size of page
	 * @return A collection of users, empty set if no user is found
	 * @since 1.2.0
	 */
	@GetMapping
	@Secured(resource = NacosAuthConfig.CONSOLE_RESOURCE_NAME_PREFIX + "users", action = ActionTypes.READ)
	public Object getUsers(@RequestParam int pageNo, @RequestParam int pageSize) {
		return userDetailsService.getUsersFromDatabase(pageNo, pageSize);
	}

	/**
	 * Login to Nacos
	 *
	 * <p>
	 * This methods uses username and password to require a new token.
	 * @param username username of user
	 * @param password password
	 * @param response http response
	 * @param request http request
	 * @return new token of the user
	 * @throws AccessException if user info is incorrect
	 */
	@PostMapping("/login")
	public Object login(@RequestParam String username, @RequestParam String password, HttpServletResponse response,
			HttpServletRequest request) throws AccessException {

		if (AuthSystemTypes.NACOS.name().equalsIgnoreCase(authConfigs.getNacosAuthSystemType())) {
			NacosUser user = (NacosUser) authManager.login(request);

			response.addHeader(NacosAuthConfig.AUTHORIZATION_HEADER, NacosAuthConfig.TOKEN_PREFIX + user.getToken());

			ObjectNode result = JacksonUtils.createEmptyJsonNode();
			// JSONObject result = new JSONObject();
			result.put(Constants.ACCESS_TOKEN, user.getToken());
			result.put(Constants.TOKEN_TTL, authConfigs.getTokenValidityInSeconds());
			result.put(Constants.GLOBAL_ADMIN, user.isGlobalAdmin());
			return result;
		}

		// 通过用户名和密码创建一个 Authentication 认证对象，实现类为 UsernamePasswordAuthenticationToken
		UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(username,
				password);

		RestResult<String> rr = new RestResult<String>();
		try {
			// 通过 AuthenticationManager（默认实现为ProviderManager）的authenticate方法验证
			// Authentication 对象
			Authentication authentication = authenticationManager.authenticate(authenticationToken);
			// 将 Authentication 绑定到 SecurityContext
			SecurityContextHolder.getContext().setAuthentication(authentication);
			// 生成Token
			String token = jwtTokenManager.createToken(authentication);
			// 将Token写入到Http头部
			response.addHeader(NacosAuthConfig.AUTHORIZATION_HEADER, "Bearer " + token);
			rr.setCode(200);
			rr.setData("Bearer " + token);
			return rr;
		}
		catch (BadCredentialsException authentication) {
			rr.setCode(401);
			rr.setMessage("Login failed");
			return rr;
		}
	}

	/**
	 * Update password.
	 * @param oldPassword old password
	 * @param newPassword new password
	 * @return Code 200 if update successfully, Code 401 if old password invalid,
	 * otherwise 500
	 */
	@PutMapping("/password")
	@Deprecated
	public RestResult<String> updatePassword(@RequestParam(value = "oldPassword") String oldPassword,
			@RequestParam(value = "newPassword") String newPassword) {

		RestResult<String> rr = new RestResult<String>();
		Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
		String username = ((UserDetails) principal).getUsername();
		User user = userDetailsService.getUserFromDatabase(username);
		String password = user.getPassword();

		// TODO: throw out more fine grained exceptions
		try {
			if (PasswordEncoderUtil.matches(oldPassword, password)) {
				userDetailsService.updateUserPassword(username, PasswordEncoderUtil.encode(newPassword));
				rr.setCode(200);
				rr.setMessage("Update password success");
			}
			else {
				rr.setCode(401);
				rr.setMessage("Old password is invalid");
			}
		}
		catch (Exception e) {
			rr.setCode(500);
			rr.setMessage("Update userpassword failed");
		}
		return rr;
	}

	/**
	 * Fuzzy matching username.
	 * @param username username
	 * @return Matched username
	 */
	@GetMapping("/search")
	@Secured(resource = NacosAuthConfig.CONSOLE_RESOURCE_NAME_PREFIX + "users", action = ActionTypes.WRITE)
	public List<String> searchUsersLikeUsername(@RequestParam String username) {
		return userDetailsService.findUserLikeUsername(username);
	}

}
