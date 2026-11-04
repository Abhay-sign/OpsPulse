package com.opspulse.service;

import com.opspulse.common.error.ConflictException;
import com.opspulse.common.error.InvalidCredentialsException;
import com.opspulse.common.error.NotFoundException;
import com.opspulse.domain.entity.UserAccount;
import com.opspulse.domain.enums.Role;
import com.opspulse.repository.UserAccountRepository;
import com.opspulse.web.dto.UserDtos.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final EntityManager em;

    @Transactional(readOnly = true)
    public List<UserResponse> list() {
        return users.findAll(Sort.by("fullName")).stream().map(UserResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return UserResponse.from(find(id));
    }

    @Transactional
    public UserResponse create(CreateUserRequest req) {
        String email = req.email().trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("A user with this email already exists");
        }
        UserAccount u = new UserAccount();
        u.setEmail(email);
        u.setFullName(req.fullName().trim());
        u.setRole(req.role());
        u.setTimezone(validZone(req.timezone() == null ? "UTC" : req.timezone()));
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        users.saveAndFlush(u);
        em.refresh(u); // pick up DB-generated created_at
        return UserResponse.from(u);
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest req) {
        UserAccount u = find(id);
        boolean losesAdmin = u.getRole() == Role.ADMIN && u.isActive()
                && ((req.role() != null && req.role() != Role.ADMIN) || Boolean.FALSE.equals(req.active()));
        if (losesAdmin) guardLastAdmin();

        if (req.fullName() != null) u.setFullName(req.fullName().trim());
        if (req.role() != null) u.setRole(req.role());
        if (req.timezone() != null) u.setTimezone(validZone(req.timezone()));
        if (req.active() != null) u.setActive(req.active());
        return UserResponse.from(u); // dirty checking writes it on commit
    }

    @Transactional
    public UserResponse updateProfile(UUID id, UpdateProfileRequest req) {
        UserAccount u = find(id);
        if (req.fullName() != null) u.setFullName(req.fullName().trim());
        if (req.timezone() != null) u.setTimezone(validZone(req.timezone()));
        return UserResponse.from(u);
    }

    @Transactional
    public void changePassword(UUID id, ChangePasswordRequest req) {
        UserAccount u = find(id);
        if (!passwordEncoder.matches(req.currentPassword(), u.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        u.setPasswordHash(passwordEncoder.encode(req.newPassword()));
    }

    /**
     * Soft delete. Users are referenced by incidents, timeline events and
     * notifications; hard-deleting would either fail on FKs or erase who
     * acknowledged what -- the audit trail must survive people leaving.
     */
    @Transactional
    public void deactivate(UUID id) {
        update(id, new UpdateUserRequest(null, null, null, false));
    }

    /** Nobody, including an admin acting on themselves, may remove the last way in. */
    private void guardLastAdmin() {
        if (users.countByRoleAndActiveTrue(Role.ADMIN) <= 1) {
            throw new ConflictException("Cannot remove the last active admin");
        }
    }

    private UserAccount find(UUID id) {
        return users.findById(id).orElseThrow(() -> new NotFoundException("User", id));
    }

    private static String validZone(String zone) {
        try {
            return ZoneId.of(zone).getId();
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Unknown timezone: " + zone);
        }
    }
}
