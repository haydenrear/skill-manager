docker-compose up -d || true
./skill-manager gateway up 
SKILL_REGISTRY_ALLOW_FILE_UPLOAD=TRUE ./skill-manager-server
