#!/usr/bin/env bash
# One-time Azure setup for the AnyDown backend.
#
# Run it in Azure Cloud Shell (Bash), which already has the az CLI and is
# signed in — it works from a phone browser at https://shell.azure.com:
#
#   curl -fsSL https://raw.githubusercontent.com/vikas-kumawatt/AnyDown/main/deploy/azure-setup.sh \
#     | bash -s -- https://YOUR-SITE.netlify.app
#
# It creates a resource group, a Container Apps environment and an app that
# scales to zero, then prints the two values to paste into GitHub and Netlify.
# After that, every push to main that touches backend/ deploys automatically
# (.github/workflows/backend-azure.yml). Safe to re-run: existing resources
# are reused, but the app key and the deploy credential are regenerated.
set -euo pipefail

FRONTEND_URL="${1:?Pass your Netlify site URL, e.g. https://anydown.netlify.app}"
FRONTEND_URL="${FRONTEND_URL%/}"
LOCATION="${LOCATION:-centralindia}"
RG="anydown"
ENV_NAME="anydown-env"
APP="anydown-api"

echo "==> Preparing the az CLI"
az extension add --name containerapp --upgrade --only-show-errors
az provider register --namespace Microsoft.App --wait
az provider register --namespace Microsoft.OperationalInsights --wait

echo "==> Resource group $RG in $LOCATION"
az group create --name "$RG" --location "$LOCATION" --output none

echo "==> Container Apps environment"
# No Log Analytics workspace: it bills per GB of logs. Live logs still work:
#   az containerapp logs show -n anydown-api -g anydown --follow
if ! az containerapp env show --name "$ENV_NAME" --resource-group "$RG" --output none 2>/dev/null; then
  az containerapp env create --name "$ENV_NAME" --resource-group "$RG" \
    --location "$LOCATION" --logs-destination none --output none
fi

APP_KEY="$(openssl rand -hex 24)"
SETTINGS=(
  "APP_APP_KEY=secretref:app-key"
  "APP_CORS_ORIGINS=$FRONTEND_URL"
  "APP_RATE_LIMITS=10/minute,100/day"
  "APP_MAX_CONCURRENT_DOWNLOADS=2"
  "APP_LOG_LEVEL=INFO"
)

echo "==> Container app $APP"
if az containerapp show --name "$APP" --resource-group "$RG" --output none 2>/dev/null; then
  az containerapp secret set --name "$APP" --resource-group "$RG" \
    --secrets "app-key=$APP_KEY" --output none
  az containerapp update --name "$APP" --resource-group "$RG" \
    --set-env-vars "${SETTINGS[@]}" --output none
else
  # A placeholder image until the first GitHub deploy replaces it. One
  # replica at most: download slots and caches live in the process.
  az containerapp create --name "$APP" --resource-group "$RG" \
    --environment "$ENV_NAME" \
    --image mcr.microsoft.com/k8se/quickstart:latest \
    --target-port 80 --ingress external \
    --min-replicas 0 --max-replicas 1 \
    --cpu 0.5 --memory 1.0Gi \
    --secrets "app-key=$APP_KEY" \
    --env-vars "${SETTINGS[@]}" \
    --output none
fi

echo "==> Deploy credential for GitHub Actions (scoped to $RG only)"
SCOPE="$(az group show --name "$RG" --query id --output tsv)"
CREDS="$(az ad sp create-for-rbac --name "anydown-github-deploy" \
  --role contributor --scopes "$SCOPE" --json-auth --only-show-errors)"

FQDN="$(az containerapp show --name "$APP" --resource-group "$RG" \
  --query properties.configuration.ingress.fqdn --output tsv)"

cat <<DONE

==========================================================================
Done. Backend URL: https://$FQDN

1) GitHub -> AnyDown -> Settings -> Secrets and variables -> Actions
   New repository secret  AZURE_CREDENTIALS  = everything between the lines:
--------------------------------------------------------------------------
$CREDS
--------------------------------------------------------------------------

2) Netlify -> your site -> Site configuration -> Environment variables
   VITE_API_BASE = https://$FQDN
   VITE_APP_KEY  = $APP_KEY

Then run "Backend -> Azure" from GitHub's Actions tab.
==========================================================================
DONE
