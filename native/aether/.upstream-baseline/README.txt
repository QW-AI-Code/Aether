Pristine upstream copies of the files this app patches, at core 1.7.0.

Do not edit, and NEVER copy a patched file in here: sync-core.sh uses these
as the merge base, so a patched baseline makes the app patch look like an
upstream deletion and the next automatic upgrade drops it silently. That is
exactly what happened to wg_prober.rs in 1.2.5 and was fixed in 1.2.6.

Every app patch is wrapped in AETHER-APP-PATCH markers, and sync-core.sh can
rebuild a pristine base from those markers if this cache is ever polluted.
