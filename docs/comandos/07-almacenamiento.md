# Almacenamiento: PersistentVolumes y PVC

El modelo de almacenamiento de Kubernetes distingue **PersistentVolumes (PV)** —el recurso de almacenamiento del clúster— y **PersistentVolumeClaims (PVC)** —la solicitud de almacenamiento que hacen las aplicaciones (ver [bases-de-datos.md](../casos-de-uso/bases-de-datos.md) y [batch-y-almacenamiento.md](../casos-de-uso/batch-y-almacenamiento.md)).

## `kubectl get pv`

Lista **todos los PersistentVolumes** del clúster (los PV son recursos de ámbito de clúster, no de namespace), con su capacidad, clase de almacenamiento, estado y reclamación.

```text
kubectl get pv
```

Columnas típicas: `NAME`, `CAPACITY`, `STORAGECLASS`, `ACCESS MODES`, `RECLAIM POLICY`, `STATUS` (Available/Bound/Released/Failed), `CLAIM`.

## `kubectl get pvc`

Lista **todos los PersistentVolumeClaims** del namespace actual.

```text
kubectl get pvc
kubectl get pvc -A          # todos los namespaces
```

## `kubectl describe pv <pv_name>`

Muestra **información detallada de un PV**: capacidad, volumen subyacente (proveedor CSI, ID de volumen), access modes, reclaim policy, clase de almacenamiento y a qué PVC está vinculado.

```text
kubectl describe pv <pv_name>
```

## `kubectl describe pvc <pvc_name>`

Muestra **información detallada de un PVC**: capacidad solicitada, clase de almacenamiento, access modes, estado y a qué PV está vinculado.

```text
kubectl describe pvc <pvc_name>
```

## Modelo en resumen

```text
StorageClass (define el tipo/provisioner)
      │  aprovisiona
      ▼
PersistentVolume (PV)   ← recurso del clúster
      ▲  vinculado
PersistentVolumeClaim (PVC)  ← pedido de la aplicación
      ▲  montado por kubelet (CSI)
Pod / Deployment / StatefulSet
```

- **PV**: recurso de clúster, aprovisionado por una `StorageClass` (o estático).
- **PVC**: pedido de la aplicación dentro de un namespace; se vincula a un PV que satisface capacidad/access mode.
- El `kubelet` monta el volumen en el Pod usando la interfaz **CSI** (ver [06-kubelet.md](../arquitectura/06-kubelet.md) y [09-addons.md](../arquitectura/09-addons.md)).

## Resumen

| Comando | Qué hace |
| --- | --- |
| `kubectl get pv` | Lista PersistentVolumes del clúster |
| `kubectl get pvc` | Lista PersistentVolumeClaims del namespace |
| `kubectl describe pv <pv>` | Detalla un volumen persistente |
| `kubectl describe pvc <pvc>` | Detalla una reclamación de volumen |