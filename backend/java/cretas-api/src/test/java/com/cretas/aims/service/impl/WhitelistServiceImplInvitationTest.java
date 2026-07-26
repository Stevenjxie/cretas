package com.cretas.aims.service.impl;

import com.cretas.aims.dto.WhitelistDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.Whitelist;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.enums.WhitelistStatus;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WhitelistRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WhitelistServiceImplInvitationTest {

    @Mock WhitelistRepository whitelistRepository;
    @Mock UserRepository userRepository;

    @Test
    void batchAddPersistsInvitedFactoryRole() {
        when(whitelistRepository.existsByFactoryIdAndPhoneNumber("LIUSHANMEN", "13800138035"))
                .thenReturn(false);
        when(whitelistRepository.save(any(Whitelist.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WhitelistServiceImpl service = new WhitelistServiceImpl(whitelistRepository, userRepository);
        WhitelistDTO.BatchResult result = service.batchAdd(
                "LIUSHANMEN",
                77L,
                WhitelistDTO.BatchAddRequest.builder()
                        .entries(List.of(WhitelistDTO.WhitelistEntry.builder()
                                .phoneNumber("13800138035")
                                .name("受邀员工")
                                .build()))
                        .role("yield_operator")
                        .department("production")
                        .build());

        ArgumentCaptor<Whitelist> saved = ArgumentCaptor.forClass(Whitelist.class);
        verify(whitelistRepository).save(saved.capture());
        assertThat(saved.getValue().getInvitedRoleCode()).isEqualTo(FactoryUserRole.yield_operator);
        assertThat(saved.getValue().getFactoryId()).isEqualTo("LIUSHANMEN");
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailedCount()).isZero();
    }

    @Test
    void platformAdminCannotBeInvitedThroughFactoryWhitelist() {
        when(whitelistRepository.existsByFactoryIdAndPhoneNumber("LIUSHANMEN", "13800138036"))
                .thenReturn(false);
        WhitelistServiceImpl service = new WhitelistServiceImpl(whitelistRepository, userRepository);

        WhitelistDTO.BatchResult result = service.batchAdd(
                "LIUSHANMEN",
                77L,
                WhitelistDTO.BatchAddRequest.builder()
                        .entries(List.of(WhitelistDTO.WhitelistEntry.builder()
                                .phoneNumber("13800138036")
                                .build()))
                        .role("platform_admin")
                        .build());

        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getFailedCount()).isEqualTo(1);
        assertThat(result.getFailedEntries().get(0).getReason())
                .contains("不能用于工厂白名单邀请");
    }

    @Test
    void batchDeleteRejectsConsumedInvitation() {
        Whitelist whitelist = Whitelist.builder()
                .id(45)
                .factoryId("LIUSHANMEN")
                .phoneNumber("13800138037")
                .status(WhitelistStatus.ACTIVE)
                .build();
        when(whitelistRepository.findAllById(List.of(45))).thenReturn(List.of(whitelist));
        when(userRepository.findByFactoryIdAndPhone("LIUSHANMEN", "13800138037"))
                .thenReturn(Optional.of(new User()));

        WhitelistServiceImpl service = new WhitelistServiceImpl(whitelistRepository, userRepository);

        assertThatThrownBy(() -> service.batchDelete("LIUSHANMEN", List.of(45)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已完成开户");
        verify(whitelistRepository, never()).batchDelete(anyList(), anyString());
    }
}
