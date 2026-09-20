package com.fitness.repository;

import com.fitness.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /** 根据手机号查用户 — 登录/注册判断使用 */
    Optional<User> findByPhone(String phone);

    /** 判断手机号是否已注册 */
    boolean existsByPhone(String phone);
}
