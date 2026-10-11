<#
.SYNOPSIS
    Automated deployment script for Google Cloud Run (Free Tier & Strict Cost Control).
.DESCRIPTION
    Builds and deploys the Java Game Server to Cloud Run in europe-west3 with:
    - 1 max instance (cost ceiling)
    - 512Mi RAM / 1 vCPU
    - 80 concurrency
    - Supabase DataSource environment variables
#>

[CmdletBinding()]
param (
    [Parameter(Mandatory = $true, HelpMessage = "Your Google Cloud Project ID (e.g. my-game-project-123)")]
    [string]$ProjectId,

    [Parameter(Mandatory = $false)]
    [string]$Region = "europe-west3",

    [Parameter(Mandatory = $false)]
    [string]$RepoName = "game-repo",

    [Parameter(Mandatory = $false)]
    [string]$ServiceName = "game-server",

    [Parameter(Mandatory = $false)]
    [string]$DatasourceUrl = $env:SPRING_DATASOURCE_URL,

    [Parameter(Mandatory = $false)]
    [string]$DatasourceUser = $env:SPRING_DATASOURCE_USERNAME,

    [Parameter(Mandatory = $false)]
    [string]$DatasourcePassword = $env:SPRING_DATASOURCE_PASSWORD
)

$ErrorActionPreference = "Stop"

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " Google Cloud Run Deployment: $ServiceName" -ForegroundColor Cyan
Write-Host " Target Project: $ProjectId ($Region)" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# 0. Check gcloud CLI
if (-not (Get-Command "gcloud" -ErrorAction SilentlyContinue)) {
    Write-Error "gcloud CLI is not installed or not in your PATH. Please install Google Cloud SDK or run this script in Google Cloud Shell."
}

# 1. Set Active Project
Write-Host "`n[1/5] Setting active project to '$ProjectId'..." -ForegroundColor Yellow
gcloud config set project $ProjectId

# 2. Enable Required APIs
Write-Host "`n[2/5] Enabling Artifact Registry, Cloud Build, and Cloud Run APIs..." -ForegroundColor Yellow
gcloud services enable artifactregistry.googleapis.com cloudbuild.googleapis.com run.googleapis.com

# 3. Create Artifact Registry Repository if not exists
Write-Host "`n[3/5] Checking Artifact Registry repository '$RepoName' in $Region..." -ForegroundColor Yellow
$repoExists = gcloud artifacts repositories describe $RepoName --location=$Region --format="value(name)" 2>$null
if (-not $repoExists) {
    Write-Host "Creating Docker repository '$RepoName'..." -ForegroundColor Green
    gcloud artifacts repositories create $RepoName `
        --repository-format=docker `
        --location=$Region `
        --description="Docker repository for $ServiceName" `
        --quiet
} else {
    Write-Host "Repository '$RepoName' already exists." -ForegroundColor Green
}

# 4. Build Container Image with Cloud Build
$ImageTag = "$Region-docker.pkg.dev/$ProjectId/$RepoName/$ServiceName`:latest"
Write-Host "`n[4/5] Building and pushing image via Google Cloud Build: $ImageTag..." -ForegroundColor Yellow
gcloud builds submit --tag $ImageTag

# 5. Deploy to Cloud Run (Strict Cost & Security Controls)
Write-Host "`n[5/5] Deploying container to Cloud Run with strict cost controls..." -ForegroundColor Yellow

$envVarsList = @()
if ($DatasourceUrl)      { $envVarsList += "SPRING_DATASOURCE_URL=$DatasourceUrl" }
if ($DatasourceUser)     { $envVarsList += "SPRING_DATASOURCE_USERNAME=$DatasourceUser" }
if ($DatasourcePassword) { $envVarsList += "SPRING_DATASOURCE_PASSWORD=$DatasourcePassword" }

$envVarsArg = if ($envVarsList.Count -gt 0) {
    "--set-env-vars=$($envVarsList -join ',')"
} else {
    "--set-env-vars=SPRING_JPA_HIBERNATE_DDL_AUTO=none"
}

gcloud run deploy $ServiceName `
    --image=$ImageTag `
    --region=$Region `
    --allow-unauthenticated `
    --max-instances=1 `
    --concurrency=80 `
    --cpu=1 `
    --memory=512Mi `
    --port=8080 `
    $envVarsArg `
    --quiet

# Retrieve Public Service URL
$ServiceUrl = gcloud run services describe $ServiceName --region=$Region --format="value(status.url)"

Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host " DEPLOYMENT SUCCESSFUL!" -ForegroundColor Green
Write-Host " Public Service URL: $ServiceUrl" -ForegroundColor Cyan
Write-Host " Health Check:       $ServiceUrl/api/health" -ForegroundColor Cyan
Write-Host " Web Client:         $ServiceUrl/" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Green
