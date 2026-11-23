// Privileged storage user-service contract.
//
// The interface is served only from a process started by Shizuku and therefore
// must stay minimal and stable: every method returns a JSON string so the
// reply stays one JSON document. `destroy` keeps the reserved transaction
// id used by the Shizuku user-service lifecycle.
//
// All call methods carry explicit transaction ids so the interface never mixes
// implicit and explicit ids (the AIDL compiler rejects that mix). The ids are
// stable and must not be reordered: inspect=0, prepare=1, start=2, status=3,
// migrationInfo=4, prepareMigration=5, startMigration=6, migrationStatus=7,
// migrationAppsPage=8, prepareForcedReallocation=9,
// and the mandatory Shizuku lifecycle destroy=16777114.
package io.github.mydanyi.armadatf;

interface IStorageService {
    // Read-only disk/volume/application inspection.
    String inspect() = 0;

    // Destructive whole-card partition flow (prepare -> start -> status).
    String prepare(String diskId, int linuxPercent) = 1;
    String start(String token, boolean confirmed) = 2;
    String status() = 3;

    // Read-only live card/migration inspection for one disk.
    String migrationInfo(String diskId) = 4;

    // Platform-native primary-storage/application migration flow
    // (prepare -> start -> migrationStatus). Prepare is read-only and captures
    // a one-use request token; start consumes it after explicit confirmation.
    String prepareMigration(String requestJson) = 5;
    String startMigration(String token, boolean confirmed) = 6;
    // The numeric id is only meaningful inside the framework instance that
    // issued it, so the caller names its expected instance; any other
    // namespace is refused with move_identity_unverified.
    String migrationStatus(int moveId, String expectedFrameworkId) = 7;

    // Bounded page of the inventory snapshot named by apps_token in
    // migrationInfo. offset starts at 0, total is the stable row count and
    // next_offset advances until total; a stale token or other caller fails.
    String migrationAppsPage(String token, int offset, int limit) = 8;

    // Prepares the same partition plan as prepare, but explicitly binds a
    // forced reallocation to this one prepared token. A later prepare replaces
    // it; the force state is reset together with the token/plan on consume or
    // invalidation. Only the move-back and app-query guards may be overridden.
    String prepareForcedReallocation(String diskId, int linuxPercent) = 9;

    void destroy() = 16777114;
}
