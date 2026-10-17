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
# Free and student subscriptions may only deploy to a short, per-subscription
# list of regions, and Azure doesn't say which. Unless LOCATION is set, these
# are tried in order, nearest to India first, until one is allowed.
REGIONS="${LOCATION:-centralindia southindia southeastasia eastasia uaenorth koreacentral japaneast westeurope northeurope swedencentral eastus eastus2 westus2 westus3 canadacentral}"
RG="anydown"
ENV_NAME="anydown-env"
APP="anydown-api"

echo "==> Preparing the az CLI"
az extension add --name containerapp --upgrade --only-show-errors
az provider register --namespace Microsoft.App --wait
az provider register --namespace Microsoft.OperationalInsights --wait

echo "==> Container Apps environment"
# No Log Analytics workspace: it bills per GB of logs. Live logs still work:
#   az containerapp logs show -n anydown-api -g anydown --follow
if az group show --name "$RG" --output none 2>/dev/null \
  && az containerapp env show --name "$ENV_NAME" --resource-group "$RG" --output none 2>/dev/null; then
  LOCATION="$(az containerapp env show --name "$ENV_NAME" --resource-group "$RG" --query location --output tsv)"
else
  LOCATION=""
  for region in $REGIONS; do
    echo "    trying $region"
    # The group only holds metadata, so it stays wherever it was first made.
    az group show --name "$RG" --output none 2>/dev/null \
      || az group create --name "$RG" --location "$region" --output none
    if az containerapp env create --name "$ENV_NAME" --resource-group "$RG" \
      --location "$region" --logs-destination none --output none 2>/tmp/anydown-env.err; then
      LOCATION="$region"
      break
    fi
    if ! grep -q "RequestDisallowedByAzure\|not available\|NoRegisteredProviderFound\|LocationNotAvailable" /tmp/anydown-env.err; then
      cat /tmp/anydown-env.err >&2
      exit 1
    fi
  done
  if [ -z "$LOCATION" ]; then
    echo "None of these regions is allowed for this subscription: $REGIONS" >&2
    echo "Find yours under Azure Policy -> Assignments, then re-run with LOCATION=<region>." >&2
    exit 1
  fi
fi
echo "    using $LOCATION"

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
