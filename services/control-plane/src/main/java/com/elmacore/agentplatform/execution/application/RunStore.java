package com.elmacore.agentplatform.execution.application;

import com.elmacore.agentplatform.execution.domain.RunResult;
import java.util.UUID;

public interface RunStore {
    void admit(UUID tenant);
    void insert(UUID tenant,UUID agent,UUID user,UUID run,String question);
    RunResult get(UUID tenant,UUID agent,UUID user,UUID run);
    boolean finish(UUID tenant,UUID agent,UUID user,RunResult result);
    void recover(UUID tenant,UUID agent,UUID user);
}
