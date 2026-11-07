# Higiene de seguridad del repositorio

El `.gitignore` excluye `target/`, `.aws-sam/`, `samconfig.toml`, `.env*`, credenciales locales y `frontend/tokens.txt`.

Antes de cada publicación:
1. Ejecutar `git status`.
2. Revisar `git diff --cached` y `git diff --cached --name-only`.
3. Buscar secretos en archivos preparados.
4. No subir tokens JWT, archivos `.env`, claves privadas, perfiles AWS, credenciales, dumps ni capturas que revelen secretos.
5. Si un secreto llegó a un commit, quitarlo del historial no revoca el secreto: debe rotarse/revocarse primero.

El `client_id` de una SPA Auth0 suele ser un identificador público; nunca incluir un `client_secret` en frontend.
