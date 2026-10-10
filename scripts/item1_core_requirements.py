"""用户批准的核心交付范围；完整856项门禁继续独立保留。"""
CHECKPOINTS = {
    'P1.1': ('folder-preservation', 'link-escape-rejected', 'source-limit-rejected'),
    'P1.2': ('github-public-branch-commit', 'duplicate-import', 'cross-owner-rejected'),
    'P1.3': ('private-auth-implementation', 'credential-encryption-lease', 'credential-revoke-late-result'),
    'P2.1': ('static-identify-install-run', 'vue-flask-identify-install-run'),
    'P2.2': ('approval-rejection-no-execution', 'install-without-run', 'selected-service-only', 'shared-step-once'),
    'P2.3': ('partial-failure-keeps-success', 'retry-failed-only', 'browser-own-backend'),
    'P3.1': ('formal-sse-model-edit', 'formal-websocket-model-edit', 'worker-persistent-ready'),
    'P3.2': ('secret-model-request', 'secret-tools-logs-memory', 'shell-source-not-authorized'),
    'P3.3': ('java-published-bytes', 'old-plan-invalidated', 'reapproved-business-correct', 'success-after-cleanup'),
    'P4.1': ('cancel-late-result', 'version-conflict', 'cross-app-isolation'),
    'P4.2': ('real-old-move-interruption-recovery', 'deterministic-publication-windows'),
    'P4.3': ('restart-stops-executors', 'restart-resource-reclamation', 'stop-failure-retains-slot'),
    'P5.1': ('real-docker-cpu-memory-swap-pids', 'real-disk-limit', 'timeout-output-limit'),
    'P5.2': ('gitee-new-rejected', 'gitee-history-preserved'),
    'P5.3': ('product-browser-import-approve-run-log-error', 'non-import-regression'),
    'P6.1': ('final-affected-checks', 'standard-restart-ready', 'post-restart-business'),
    'P6.2': ('clean-source-real-model-business-replay',),
    'P6.3': ('independent-resource-cleanup', 'replay-evidence-rollback', 'core-gate-negative-tests'),
}

DEFERRED = ('private-remote-permission-revocation-matrix', 'extended-frameworks',
            'vue-java-maven', 'multi-browser-os', 'long-pressure', 'original-856-matrix')


def profile():
    return {'schemaVersion': 1, 'scope': 'item1-core-20261007', 'supportedProviders': ['GitHub'],
            'checkpoints': [{'id': key, 'assertions': list(values)} for key, values in CHECKPOINTS.items()],
            'deferred': list(DEFERRED)}
