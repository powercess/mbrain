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

# Commit and pull request titles

- Every commit title and pull request title must use `type: description` or `type(scope): description`.
- The scope is optional. Use a colon followed by a space, and a concise, non-empty description.
- Titles may be written in Chinese or English.
- Examples: `fix: 修复保存按钮遮挡`, `fix(ui): keep the save button visible`, `docs: 更新开发规范`.
- Apply the same format to merge and squash commit titles when creating or editing them.

# UI style skill

- For MBrain UI changes or reviews, read and apply [.agents/skills/mbrain-style/SKILL.md](.agents/skills/mbrain-style/SKILL.md) before editing.
- Keep visual values in DESIGN.md and shared implementations in DesignSystem.kt; do not create per-page variants of grouped action rows.
- After each application/UI change, rebuild and verify on the connected test device. Skill/documentation-only changes require lightweight validation.
- Do not create a pull request until the user explicitly requests it.
