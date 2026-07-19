#!/bin/bash
# ==============================================
# SmartBI PostgreSQL Setup Script
# ==============================================
# Installs PostgreSQL and creates smartbi_db database
# For CentOS/Alibaba Cloud Linux servers
#
# Usage:
#   chmod +x setup_postgres_smartbi.sh
#   sudo ./setup_postgres_smartbi.sh
# ==============================================

set -e

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}SmartBI PostgreSQL Setup${NC}"
echo -e "${GREEN}========================================${NC}"

# Configuration (change as needed)
PG_VERSION="15"
SMARTBI_DB="smartbi_db"
SMARTBI_USER="smartbi_user"
SMARTBI_PASSWORD="${SMARTBI_PASSWORD:?Set SMARTBI_PASSWORD from a protected environment source}"

# Check if running as root
if [ "$EUID" -ne 0 ]; then
    echo -e "${RED}Please run as root (sudo)${NC}"
    exit 1
fi

# ==============================================
# Step 1: Install PostgreSQL
# ==============================================
echo -e "${YELLOW}Step 1: Installing PostgreSQL ${PG_VERSION}...${NC}"

# Install PostgreSQL repository
if [ ! -f /etc/yum.repos.d/pgdg-redhat-all.repo ]; then
    echo "Installing PostgreSQL repository..."
    yum install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-7-x86_64/pgdg-redhat-repo-latest.noarch.rpm 2>/dev/null || \
    yum install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-8-x86_64/pgdg-redhat-repo-latest.noarch.rpm 2>/dev/null || \
    dnf install -y https://download.postgresql.org/pub/repos/yum/reporpms/EL-8-x86_64/pgdg-redhat-repo-latest.noarch.rpm 2>/dev/null
fi

# Install PostgreSQL
echo "Installing PostgreSQL ${PG_VERSION}..."
yum install -y postgresql${PG_VERSION}-server postgresql${PG_VERSION} 2>/dev/null || \
dnf install -y postgresql${PG_VERSION}-server postgresql${PG_VERSION} 2>/dev/null

# ==============================================
# Step 2: Initialize and Start PostgreSQL
# ==============================================
echo -e "${YELLOW}Step 2: Initializing PostgreSQL...${NC}"

# Initialize database if not already done
if [ ! -f /var/lib/pgsql/${PG_VERSION}/data/PG_VERSION ]; then
    /usr/pgsql-${PG_VERSION}/bin/postgresql-${PG_VERSION}-setup initdb
fi

# Start and enable service
systemctl start postgresql-${PG_VERSION}
systemctl enable postgresql-${PG_VERSION}

echo "PostgreSQL ${PG_VERSION} is running"

# ==============================================
# Step 3: Configure PostgreSQL
# ==============================================
echo -e "${YELLOW}Step 3: Configuring PostgreSQL...${NC}"

# Allow password authentication for local connections
PG_HBA="/var/lib/pgsql/${PG_VERSION}/data/pg_hba.conf"
if ! grep -q "host.*smartbi_db.*md5" "$PG_HBA"; then
    echo "Adding authentication rules..."
    # Backup original
    cp "$PG_HBA" "${PG_HBA}.bak"

    # Add rules for smartbi_db
    sed -i '/^host.*all.*all.*127.0.0.1/a\host    smartbi_db      smartbi_user    127.0.0.1/32            md5' "$PG_HBA"
    sed -i '/^host.*all.*all.*::1/a\host    smartbi_db      smartbi_user    ::1/128                 md5' "$PG_HBA"

    # Also add localhost rule
    echo "local   smartbi_db      smartbi_user                            md5" >> "$PG_HBA"
fi

# Reload configuration
systemctl reload postgresql-${PG_VERSION}

# ==============================================
# Step 4: Create Database and User
# ==============================================
echo -e "${YELLOW}Step 4: Creating database and user...${NC}"

# Create user and database as postgres user
sudo -u postgres psql << EOF
-- Create user if not exists
DO \$\$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${SMARTBI_USER}') THEN
        CREATE USER ${SMARTBI_USER} WITH PASSWORD '${SMARTBI_PASSWORD}';
    ELSE
        ALTER USER ${SMARTBI_USER} WITH PASSWORD '${SMARTBI_PASSWORD}';
    END IF;
END
\$\$;

-- Create database if not exists
SELECT 'CREATE DATABASE ${SMARTBI_DB} OWNER ${SMARTBI_USER}'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${SMARTBI_DB}')\gexec

-- Grant privileges
GRANT ALL PRIVILEGES ON DATABASE ${SMARTBI_DB} TO ${SMARTBI_USER};
EOF

echo "Database '${SMARTBI_DB}' and user '${SMARTBI_USER}' created"

# ==============================================
# Step 5: Create Tables
# ==============================================
echo -e "${YELLOW}Step 5: Creating SmartBI tables...${NC}"

# Check if DDL script exists
DDL_SCRIPT="/www/wwwroot/cretas/code/scripts/postgres_smartbi_ddl.sql"
if [ -f "$DDL_SCRIPT" ]; then
    PGPASSWORD=${SMARTBI_PASSWORD} psql -h localhost -U ${SMARTBI_USER} -d ${SMARTBI_DB} -f "$DDL_SCRIPT"
    echo "Tables created from DDL script"
else
    echo -e "${YELLOW}DDL script not found at ${DDL_SCRIPT}${NC}"
    echo "Please run the DDL script manually after setup"
fi

# ==============================================
# Step 6: Verify Setup
# ==============================================
echo -e "${YELLOW}Step 6: Verifying setup...${NC}"

# Test connection
if PGPASSWORD=${SMARTBI_PASSWORD} psql -h localhost -U ${SMARTBI_USER} -d ${SMARTBI_DB} -c "SELECT 1" > /dev/null 2>&1; then
    echo -e "${GREEN}Connection test: SUCCESS${NC}"
else
    echo -e "${RED}Connection test: FAILED${NC}"
    exit 1
fi

# List tables
echo "Existing tables:"
PGPASSWORD=${SMARTBI_PASSWORD} psql -h localhost -U ${SMARTBI_USER} -d ${SMARTBI_DB} -c "\dt" 2>/dev/null || echo "No tables yet"

# ==============================================
# Final Summary
# ==============================================
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}Setup Complete!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "PostgreSQL ${PG_VERSION} is installed and running"
echo ""
echo "Connection Details:"
echo "  Host:     localhost"
echo "  Port:     5432"
echo "  Database: ${SMARTBI_DB}"
echo "  User:     ${SMARTBI_USER}"
echo "  Password: ${SMARTBI_PASSWORD}"
echo ""
echo "JDBC URL:"
echo "  jdbc:postgresql://localhost:5432/${SMARTBI_DB}"
echo ""
echo "Python URL:"
echo "  postgresql://${SMARTBI_USER}:****@localhost:5432/${SMARTBI_DB}"
echo ""
echo -e "${YELLOW}IMPORTANT: Change the default password in production!${NC}"
echo ""
echo "To connect manually:"
echo "  PGPASSWORD=${SMARTBI_PASSWORD} psql -h localhost -U ${SMARTBI_USER} -d ${SMARTBI_DB}"
echo ""
