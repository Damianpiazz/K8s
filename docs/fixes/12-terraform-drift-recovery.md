# Fix 12: Drift de estado Terraform tras la recreación del emulador

La recreación del emulador Floci-AZ dejó el estado remoto de Terraform
inconsistente con la infraestructura real: recursos de red que Terraform
gestionaba desaparecieron del estado. Se recuperó aplicando por objetivo los
módulos afectados.

## Error observado

`terraform plan` mostraba:

```text
8 to add, 2 to change
```

La VNet, la subnet y la asociación del NSG figuraban como ausentes en el
estado remoto tras la recreación del entorno.

## Por qué ocurre

La recreación del emulador borró los recursos de red anclados al entorno
(VNet, subnet, asociación de NSG), dejando la infraestructura real
inconsistente con el estado remoto que Terraform tenía registrado. Terraform
detectó esos recursos como perdidos y proponía recrearlos (`to add`) o
corregirlos (`to change`).

## Fix aplicado

Reaplicar por objetivo solo los módulos afectados:

```bash
terraform apply -auto-approve -target module.networking -target module.registry -target module.databases
```

- El **primer intento falló** con un `404` en un security rule del NSG (el
  recurso ya no existía en Azure).
- El **re-run convergió**: `Apply complete! Resources: 1 added, 2 changed`.

Tras la convergencia, el plan final solo muestra el clúster AKS por agregar:
`module.aks.azurerm_kubernetes_cluster.this`.

## Cómo verificar

```bash
terraform plan
```

El plan no debe proponer adiciones fuera del módulo AKS: el clúster
(`azurerm_kubernetes_cluster`) queda pendiente de aplicar cuando corresponda.