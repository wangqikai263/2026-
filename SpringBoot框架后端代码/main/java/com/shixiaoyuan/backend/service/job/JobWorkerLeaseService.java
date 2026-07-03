package com.shixiaoyuan.backend.service.job;

/**
 * 历史兼容占位类：
 * 之前用于多后端实例的 DB 锁互斥。
 * 现阶段仅保留单后端 8080，该类不再参与任何运行逻辑。
 */
final class JobWorkerLeaseService {
    private JobWorkerLeaseService() {
    }
}
