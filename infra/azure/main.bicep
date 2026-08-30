targetScope = 'resourceGroup'

@description('Azure region used for all regional resources.')
param location string = resourceGroup().location

@allowed([
  'production'
])
@description('Stable environment label used in resource names and tags.')
param environmentName string = 'production'

@minLength(40)
@maxLength(40)
@description('Immutable image tag, normally the Git commit SHA.')
param imageTag string

@description('Create the application revision after its images have been pushed.')
param deployApplication bool = false

@minLength(71)
@maxLength(71)
@description('Immutable backend manifest digest in sha256:<64 hex characters> form; required when deploying the app.')
param backendImageDigest string = 'sha256:0000000000000000000000000000000000000000000000000000000000000000'

@minLength(71)
@maxLength(71)
@description('Immutable frontend manifest digest in sha256:<64 hex characters> form; required when deploying the app.')
param frontendImageDigest string = 'sha256:0000000000000000000000000000000000000000000000000000000000000000'

@minLength(71)
@maxLength(71)
@description('Immutable database bootstrap manifest digest in sha256:<64 hex characters> form; required when deploying the app.')
param databaseBootstrapImageDigest string = 'sha256:0000000000000000000000000000000000000000000000000000000000000000'

@minLength(1)
@maxLength(64)
@description('Lowercase Container Apps revision suffix used to force an explicit rollout.')
param rolloutSuffix string = 'r-${take(toLower(imageTag), 12)}'

@minLength(1)
@maxLength(63)
param postgresAdministratorLogin string = 'assetpulse_admin'

@secure()
@minLength(12)
@maxLength(128)
param postgresAdministratorPassword string

@minLength(1)
@maxLength(63)
param applicationDatabaseUsername string = 'assetpulse_app'

@secure()
@minLength(12)
@maxLength(128)
param applicationDatabasePassword string

@minLength(36)
@maxLength(36)
@description('Object ID of the OpenID Connect deployment principal that pushes images.')
param deploymentPrincipalObjectId string

var resourceToken = uniqueString(subscription().subscriptionId, resourceGroup().id, environmentName)
var namePrefix = 'assetpulse-${environmentName}'
var registryName = take('assetpulse${resourceToken}', 50)
var postgresServerName = take('${namePrefix}-pg-${resourceToken}', 63)
var postgresDatabaseName = 'assetpulse'
var postgresPrivateDnsZoneName = '${namePrefix}-${resourceToken}.private.postgres.database.azure.com'
var containerAppName = '${namePrefix}-app'
var containerRegistryLoginServer = containerRegistry.properties.loginServer
var repositoryReaderRoleDefinitionId = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  'b93aa761-3e63-49ed-ac28-beffa264f7ac'
)
var repositoryWriterRoleDefinitionId = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  '2a1e307c-b015-4ebd-883e-5b7698a07328'
)
var repositoryCatalogListerRoleDefinitionId = subscriptionResourceId(
  'Microsoft.Authorization/roleDefinitions',
  'bfdb9389-c9a5-478a-bb2f-ba9ca092c3c7'
)
var commonTags = {
  application: 'assetpulse'
  environment: environmentName
  managedBy: 'bicep'
}

resource virtualNetwork 'Microsoft.Network/virtualNetworks@2025-07-01' = {
  name: '${namePrefix}-vnet'
  location: location
  tags: commonTags
  properties: {
    addressSpace: {
      addressPrefixes: [
        '10.20.0.0/16'
      ]
    }
  }
}

resource containerAppsSubnet 'Microsoft.Network/virtualNetworks/subnets@2025-07-01' = {
  parent: virtualNetwork
  name: 'container-apps'
  properties: {
    addressPrefix: '10.20.0.0/23'
    delegations: [
      {
        name: 'container-apps-environment'
        properties: {
          serviceName: 'Microsoft.App/environments'
        }
      }
    ]
  }
}

resource postgresSubnet 'Microsoft.Network/virtualNetworks/subnets@2025-07-01' = {
  parent: virtualNetwork
  name: 'postgres'
  properties: {
    addressPrefix: '10.20.2.0/24'
    delegations: [
      {
        name: 'postgres-flexible-server'
        properties: {
          serviceName: 'Microsoft.DBforPostgreSQL/flexibleServers'
        }
      }
    ]
    serviceEndpoints: [
      {
        service: 'Microsoft.Storage'
        locations: [
          location
        ]
      }
    ]
  }
  dependsOn: [
    containerAppsSubnet
  ]
}

resource postgresPrivateDnsZone 'Microsoft.Network/privateDnsZones@2024-06-01' = {
  name: postgresPrivateDnsZoneName
  location: 'global'
  tags: commonTags
  properties: {}
}

resource postgresPrivateDnsLink 'Microsoft.Network/privateDnsZones/virtualNetworkLinks@2024-06-01' = {
  parent: postgresPrivateDnsZone
  name: '${namePrefix}-vnet-link'
  location: 'global'
  tags: commonTags
  properties: {
    registrationEnabled: false
    virtualNetwork: {
      id: virtualNetwork.id
    }
  }
}

resource postgresServer 'Microsoft.DBforPostgreSQL/flexibleServers@2025-08-01' = {
  name: postgresServerName
  location: location
  tags: commonTags
  sku: {
    name: 'Standard_B1ms'
    tier: 'Burstable'
  }
  properties: {
    administratorLogin: postgresAdministratorLogin
    administratorLoginPassword: postgresAdministratorPassword
    authConfig: {
      activeDirectoryAuth: 'Disabled'
      passwordAuth: 'Enabled'
    }
    backup: {
      backupRetentionDays: 7
      geoRedundantBackup: 'Disabled'
    }
    highAvailability: {
      mode: 'Disabled'
    }
    network: {
      delegatedSubnetResourceId: postgresSubnet.id
      privateDnsZoneArmResourceId: postgresPrivateDnsZone.id
    }
    storage: {
      autoGrow: 'Enabled'
      storageSizeGB: 32
    }
    version: '17'
  }
  dependsOn: [
    postgresPrivateDnsLink
  ]
}

resource postgresDatabase 'Microsoft.DBforPostgreSQL/flexibleServers/databases@2025-08-01' = {
  parent: postgresServer
  name: postgresDatabaseName
  properties: {
    charset: 'UTF8'
    collation: 'en_US.UTF8'
  }
}

resource logAnalyticsWorkspace 'Microsoft.OperationalInsights/workspaces@2025-07-01' = {
  name: '${namePrefix}-logs'
  location: location
  tags: commonTags
  properties: {
    features: {
      enableLogAccessUsingOnlyResourcePermissions: true
    }
    publicNetworkAccessForIngestion: 'Enabled'
    publicNetworkAccessForQuery: 'Enabled'
    retentionInDays: 30
    sku: {
      name: 'PerGB2018'
    }
  }
}

resource containerAppsEnvironment 'Microsoft.App/managedEnvironments@2026-01-01' = {
  name: '${namePrefix}-environment'
  location: location
  tags: commonTags
  properties: {
    publicNetworkAccess: 'Enabled'
    appLogsConfiguration: {
      destination: 'log-analytics'
      logAnalyticsConfiguration: {
        customerId: logAnalyticsWorkspace.properties.customerId
        sharedKey: logAnalyticsWorkspace.listKeys().primarySharedKey
      }
    }
    vnetConfiguration: {
      infrastructureSubnetId: containerAppsSubnet.id
      internal: false
    }
    workloadProfiles: [
      {
        name: 'Consumption'
        workloadProfileType: 'Consumption'
      }
    ]
    zoneRedundant: false
  }
}

resource containerRegistry 'Microsoft.ContainerRegistry/registries@2025-11-01' = {
  name: registryName
  location: location
  tags: commonTags
  sku: {
    name: 'Basic'
  }
  properties: {
    adminUserEnabled: false
    anonymousPullEnabled: false
    dataEndpointEnabled: false
    policies: {
      azureADAuthenticationAsArmPolicy: {
        status: 'enabled'
      }
    }
    publicNetworkAccess: 'Enabled'
    roleAssignmentMode: 'AbacRepositoryPermissions'
  }
}

resource containerPullIdentity 'Microsoft.ManagedIdentity/userAssignedIdentities@2024-11-30' = {
  name: '${namePrefix}-container-pull'
  location: location
  tags: commonTags
}

resource containerRegistryReaderAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(containerRegistry.id, containerPullIdentity.id, repositoryReaderRoleDefinitionId)
  scope: containerRegistry
  properties: {
    principalId: containerPullIdentity.properties.principalId
    principalType: 'ServicePrincipal'
    roleDefinitionId: repositoryReaderRoleDefinitionId
  }
}

resource containerRegistryWriterAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(containerRegistry.id, deploymentPrincipalObjectId, repositoryWriterRoleDefinitionId)
  scope: containerRegistry
  properties: {
    principalId: deploymentPrincipalObjectId
    principalType: 'ServicePrincipal'
    roleDefinitionId: repositoryWriterRoleDefinitionId
  }
}

resource containerRegistryCatalogListerAssignment 'Microsoft.Authorization/roleAssignments@2022-04-01' = {
  name: guid(containerRegistry.id, deploymentPrincipalObjectId, repositoryCatalogListerRoleDefinitionId)
  scope: containerRegistry
  properties: {
    principalId: deploymentPrincipalObjectId
    principalType: 'ServicePrincipal'
    roleDefinitionId: repositoryCatalogListerRoleDefinitionId
  }
}

resource containerApp 'Microsoft.App/containerApps@2026-01-01' = if (deployApplication) {
  name: containerAppName
  location: location
  tags: union(commonTags, {
    imageTag: imageTag
  })
  identity: {
    type: 'UserAssigned'
    userAssignedIdentities: {
      '${containerPullIdentity.id}': {}
    }
  }
  properties: {
    environmentId: containerAppsEnvironment.id
    workloadProfileName: 'Consumption'
    configuration: {
      activeRevisionsMode: 'Single'
      maxInactiveRevisions: 3
      identitySettings: [
        {
          identity: containerPullIdentity.id
          lifecycle: 'None'
        }
      ]
      ingress: {
        allowInsecure: false
        external: true
        targetPort: 8080
        transport: 'auto'
      }
      registries: [
        {
          identity: containerPullIdentity.id
          server: containerRegistryLoginServer
        }
      ]
      secrets: [
        {
          name: 'postgres-administrator-password'
          value: postgresAdministratorPassword
        }
        {
          name: 'application-database-password'
          value: applicationDatabasePassword
        }
      ]
    }
    template: {
      revisionSuffix: rolloutSuffix
      terminationGracePeriodSeconds: 30
      initContainers: [
        {
          name: 'database-role-bootstrap'
          image: '${containerRegistryLoginServer}/assetpulse-database-bootstrap@${databaseBootstrapImageDigest}'
          env: [
            {
              name: 'PGHOST'
              value: postgresServer.properties.fullyQualifiedDomainName
            }
            {
              name: 'PGPORT'
              value: '5432'
            }
            {
              name: 'PGDATABASE'
              value: postgresDatabaseName
            }
            {
              name: 'PGUSER'
              value: postgresAdministratorLogin
            }
            {
              name: 'PGPASSWORD'
              secretRef: 'postgres-administrator-password'
            }
            {
              name: 'PGSSLMODE'
              value: 'require'
            }
            {
              name: 'ASSETPULSE_APP_USERNAME'
              value: applicationDatabaseUsername
            }
            {
              name: 'ASSETPULSE_APP_PASSWORD'
              secretRef: 'application-database-password'
            }
          ]
          resources: {
            cpu: json('0.25')
            memory: '0.5Gi'
          }
        }
      ]
      containers: [
        {
          name: 'frontend'
          image: '${containerRegistryLoginServer}/assetpulse-frontend@${frontendImageDigest}'
          env: [
            {
              name: 'BACKEND_UPSTREAM'
              value: '127.0.0.1:8081'
            }
          ]
          probes: [
            {
              type: 'Startup'
              httpGet: {
                path: '/healthz'
                port: 8080
                scheme: 'HTTP'
              }
              failureThreshold: 10
              initialDelaySeconds: 1
              periodSeconds: 3
              successThreshold: 1
              timeoutSeconds: 2
            }
            {
              type: 'Liveness'
              httpGet: {
                path: '/healthz'
                port: 8080
                scheme: 'HTTP'
              }
              failureThreshold: 3
              initialDelaySeconds: 5
              periodSeconds: 15
              successThreshold: 1
              timeoutSeconds: 3
            }
            {
              type: 'Readiness'
              httpGet: {
                path: '/healthz'
                port: 8080
                scheme: 'HTTP'
              }
              failureThreshold: 3
              periodSeconds: 5
              successThreshold: 1
              timeoutSeconds: 3
            }
          ]
          resources: {
            cpu: json('0.25')
            memory: '0.5Gi'
          }
        }
        {
          name: 'backend'
          image: '${containerRegistryLoginServer}/assetpulse-backend@${backendImageDigest}'
          env: [
            {
              name: 'SERVER_PORT'
              value: '8081'
            }
            {
              name: 'SPRING_DATASOURCE_URL'
              value: 'jdbc:postgresql://${postgresServer.properties.fullyQualifiedDomainName}:5432/${postgresDatabaseName}?sslmode=require'
            }
            {
              name: 'SPRING_DATASOURCE_USERNAME'
              value: applicationDatabaseUsername
            }
            {
              name: 'SPRING_DATASOURCE_PASSWORD'
              secretRef: 'application-database-password'
            }
            {
              name: 'ASSETPULSE_SESSION_COOKIE_SECURE'
              value: 'true'
            }
            {
              name: 'ASSETPULSE_ALERT_STREAM_TIMEOUT_MILLIS'
              value: '210000'
            }
          ]
          probes: [
            {
              type: 'Startup'
              httpGet: {
                path: '/api/v1/status'
                port: 8081
                scheme: 'HTTP'
              }
              failureThreshold: 10
              initialDelaySeconds: 2
              periodSeconds: 10
              successThreshold: 1
              timeoutSeconds: 3
            }
            {
              type: 'Liveness'
              httpGet: {
                path: '/api/v1/status'
                port: 8081
                scheme: 'HTTP'
              }
              failureThreshold: 3
              initialDelaySeconds: 10
              periodSeconds: 15
              successThreshold: 1
              timeoutSeconds: 3
            }
            {
              type: 'Readiness'
              httpGet: {
                path: '/api/v1/status'
                port: 8081
                scheme: 'HTTP'
              }
              failureThreshold: 3
              periodSeconds: 5
              successThreshold: 1
              timeoutSeconds: 3
            }
          ]
          resources: {
            cpu: json('0.5')
            memory: '1Gi'
          }
        }
      ]
      scale: {
        minReplicas: 1
        maxReplicas: 1
      }
    }
  }
  dependsOn: [
    containerRegistryReaderAssignment
    postgresDatabase
  ]
}

output containerRegistryName string = containerRegistry.name
output containerRegistryLoginServer string = containerRegistryLoginServer
output containerAppName string = containerAppName
output postgresServerFullyQualifiedDomainName string = postgresServer.properties.fullyQualifiedDomainName
output applicationUrl string = deployApplication ? 'https://${containerApp!.properties.configuration.ingress.fqdn}' : ''
