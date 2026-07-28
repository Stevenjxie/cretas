import type { WorkflowPublishAndActivateRequest } from './types';

export interface WorkflowPublishIdentity {
  factoryId: string;
  productTypeId: string;
  lockVersion: number;
  revisionId: number | null;
  revisionHash: string | null;
  definitionVersion: number;
}

export interface WorkflowPublishCommand extends WorkflowPublishIdentity {
  idempotencyKey: string;
  request: WorkflowPublishAndActivateRequest;
}

export type WorkflowIdempotencyKeyFactory = () => string;

function isSameIdentity(
  command: WorkflowPublishCommand,
  identity: WorkflowPublishIdentity,
) {
  return command.factoryId === identity.factoryId
    && command.productTypeId === identity.productTypeId
    && command.lockVersion === identity.lockVersion
    && command.revisionId === identity.revisionId
    && command.revisionHash === identity.revisionHash
    && command.definitionVersion === identity.definitionVersion;
}

/**
 * Resolves the publish command from caller-owned state.
 *
 * Keeping the previous command outside this helper makes retries deterministic and
 * testable: the exact factory, product, lock version and saved revision reuse the
 * same idempotency key, while any identity/version change creates a new command.
 */
export function resolveWorkflowPublishCommand(
  previous: WorkflowPublishCommand | null,
  identity: WorkflowPublishIdentity,
  createIdempotencyKey: WorkflowIdempotencyKeyFactory,
): WorkflowPublishCommand {
  if (previous && isSameIdentity(previous, identity)) {
    return previous;
  }

  const idempotencyKey = createIdempotencyKey().trim();
  if (!idempotencyKey) {
    throw new Error('Workflow publish idempotency key must not be blank');
  }
  if (identity.revisionId == null || !identity.revisionHash) {
    throw new Error('Workflow publish requires an exact saved revision identity');
  }

  return {
    ...identity,
    idempotencyKey,
    request: {
      lockVersion: identity.lockVersion,
      idempotencyKey,
      revisionId: identity.revisionId,
      revisionHash: identity.revisionHash,
      definitionVersion: identity.definitionVersion,
    },
  };
}
