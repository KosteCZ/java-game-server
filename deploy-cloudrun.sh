#!/usr/bin/env bash
# ==============================================================================
# Google Cloud Run Automated Deployment (Free Tier & Strict Cost Control)
# ==============================================================================
set -euo pipefail

if [ -z "${1:-}" ]; then
  echo "Usage: ./deploy-cloudrun.sh <PROJECT_ID> [REGION]"
  echo "Example: ./deploy-cloudrun.sh my-gcp-project-123 europe-west3"
  exit 1
fi

PROJECT_ID="$1"
REGION="${2:-europe-west3}"
REPO_NAME="game-repo"
SERVICE_NAME="game-server"

echo "=========================================================="
echo " Deploying to Cloud Run: $SERVICE_NAME ($REGION)"
echo " Target Project: $PROJECT_ID"
echo "=========================================================="

# 1. Set active project
echo "[1/5] Setting active project..."
gcloud config set project "$PROJECT_ID"

# 2. Enable necessary APIs
echo "[2/5] Enabling Artifact Registry, Cloud Build & Cloud Run APIs..."
gcloud services enable artifactregistry.googleapis.com cloudbuild.googleapis.com run.googleapis.com

# 3. Create Artifact Registry Docker repo if it doesn't exist
echo "[3/5] Checking Artifact Registry repository '$REPO_NAME'..."
if ! gcloud artifacts repositories describe "$REPO_NAME" --location="$REGION" &>/dev/null; then
  echo "Creating repository '$REPO_NAME'..."
  gcloud artifacts repositories create "$REPO_NAME" \
    --repository-format=docker \
    --location="$REGION" \
    --description="Docker repository for $SERVICE_NAME" \
    --quiet
else
  echo "Repository '$REPO_NAME' already exists."
fi

# 4. Build with Cloud Build
IMAGE_TAG="$REGION-docker.pkg.dev/$PROJECT_ID/$REPO_NAME/$SERVICE_NAME:latest"
echo "[4/5] Building and pushing image with Cloud Build: $IMAGE_TAG..."
gcloud builds submit --tag "$IMAGE_TAG"

# 5. Deploy to Cloud Run with strict limits
echo "[5/5] Deploying service to Cloud Run..."
ENV_VARS="SPRING_JPA_HIBERNATE_DDL_AUTO=none"
if [ -n "${SPRING_DATASOURCE_URL:-}" ]; then
  ENV_VARS="$ENV_VARS,SPRING_DATASOURCE_URL=${SPRING_DATASOURCE_URL}"
fi
if [ -n "${SPRING_DATASOURCE_USERNAME:-}" ]; then
  ENV_VARS="$ENV_VARS,SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME}"
fi
if [ -n "${SPRING_DATASOURCE_PASSWORD:-}" ]; then
  ENV_VARS="$ENV_VARS,SPRING_DATASOURCE_PASSWORD=${SPRING_DATASOURCE_PASSWORD}"
fi

gcloud run deploy "$SERVICE_NAME" \
  --image="$IMAGE_TAG" \
  --region="$REGION" \
  --allow-unauthenticated \
  --max-instances=1 \
  --concurrency=80 \
  --cpu=1 \
  --memory=512Mi \
  --port=8080 \
  --set-env-vars="$ENV_VARS" \
  --quiet

SERVICE_URL=$(gcloud run services describe "$SERVICE_NAME" --region="$REGION" --format="value(status.url)")

echo "=========================================================="
echo " DEPLOYMENT SUCCESSFUL!"
echo " Public Service URL: $SERVICE_URL"
echo " Health Check:       $SERVICE_URL/api/health"
echo " Web Game Client:    $SERVICE_URL/"
echo "=========================================================="
