> [!IMPORTANT]
> This project does not accept fully AI-generated pull requests. AI tools may only be used for assistance. You must understand and take responsibility for every change you submit.
>
> Read and follow:
> • [AGENTS.md](./AGENTS.md)
> • [CONTRIBUTING.md](./CONTRIBUTING.md)

# Upstream workflow archive

The original JabRef workflows are preserved here unchanged. GitHub only discovers workflows directly in `.github/workflows`, so these files do not execute.

Group B uses `../workflows/groupb-ci-cd.yml` for builds, checks, installers, and GitHub Releases. The inherited workflows include upstream issue automation, private build-server uploads, signing credentials, Maven publication, and other upstream services. Restore an individual workflow only after reviewing its triggers, repository guards, permissions, and credentials.

<!-- markdownlint-disable-file MD041 -->
