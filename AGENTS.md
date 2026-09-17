# Repository workflow

- Never commit or push changes directly to `dev` or `main`.
- Start changes on a dedicated branch based on the latest `dev`. Use the `agent/` prefix for agent-created branches.
- Submit feature, fix, documentation and workflow changes through a pull request targeting `dev`.
- Only this repository's `dev` branch may open a release integration pull request targeting `main`.
- Do not bypass branch protections, force-push protected branches, or merge before required checks pass.
- Keep `dev` as the default branch and retain it after merging into `main`.
- Create release tags only on `main` commits and only when a release is explicitly authorized.

# Validation scope

- Choose local checks according to the changed behavior; do not run a full Android build for every PR by default.
- Documentation and repository-instruction changes need lightweight checks only. Workflow changes need workflow validation and tests of any changed CI logic.
- Run relevant builds, tests and lint for application, dependency and build-configuration changes. Repeat checks only after relevant changes or failures.
- CI keeps required checks reporting on every PR, but skips Android setup and builds for documentation-only PRs. Pushes to `dev`/`main`, manual runs and releases retain full validation.
