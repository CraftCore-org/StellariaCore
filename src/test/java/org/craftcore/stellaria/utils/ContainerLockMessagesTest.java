package org.craftcore.stellaria.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainerLockMessagesTest {

    @Test
    void suppliesRequiredMessagesWhenAnExistingMessagesFileLacksNewKeys() {
        assertEquals("&%cこのチェストは保護されています。", ContainerLockMessages.defaultRawMessage("lock.protected_chest"));
        assertEquals("&%cこのシュルカーボックスは保護されています。", ContainerLockMessages.defaultRawMessage("lock.protected_shulker_box"));
        assertEquals("&%cこの樽は保護されています。", ContainerLockMessages.defaultRawMessage("lock.protected_barrel"));
        assertEquals("&%cこのコンテナはすでに保護済みです。", ContainerLockMessages.defaultRawMessage("lock.already_locked_self"));
    }
}
