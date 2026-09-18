# Fix 22: Bootstrap de Argo CD con manifests locales (sin remote base y con `-n argocd`)

El bootstrap de Argo CD dependía de un remote base de kustomize
(`https://github.com/argoproj/argo-cd.git/manifests/cluster-install?ref=v2.12.3`)
y, al instalar desde el `install.yaml` oficial, los componentes quedaban en el
namespace `default` en lugar de `argocd`, porque el manifest de v2.12.3 **no
define** `metadata.namespace` en los objetos namespaced. Se vendió el manifest
como archivo local y el comando canónico ahora inyecta el namespace con
`-n argocd`.

## Error observado

1. **Argo CD instalado en el namespace equivocado**: tras aplicar el
   `install.yaml` oficial, los 7 workloads (`argocd-server`,
   `argocd-application-controller`, `argocd-repo-server`, etc.) aparecían en el
   namespace `default` (`kubectl get pods -A`), no en `argocd`.
2. **Bootstrap acoplado a un remote base**: `cluster/base/argocd/install/
   kustomization.yaml` declaraba
   `resources: [https://github.com/argoproj/argo-cd.git/manifests/cluster-install?ref=v2.12.3]`;
   kustomize v5 deprecó los remote bases (vía http/https) y, aunque el formato
   git-remote aún renderiza, el bootstrap quedaba a merced de la red y del
   estado del upstream (sin pin verificable ni soporte offline).

## Por qué ocurre

- El `install.yaml` de Argo CD v2.12.3 (verificado por SHA-256 contra el tag
  `v2.12.3` de `argoproj/argo-cd`:
  `A4A746D7125A537819C0919D33457A0470372FB9AE8E5CCE63B8B95B4464EDB6`)
  **no incluye** `kind: Namespace` ni `namespace: argocd` en los objetos
  namespaced: solo los `subjects` de los `ClusterRoleBinding` referencian
  `namespace: argocd`. La instalación oficial de Argo CD asume que el instalador
  inyecta el namespace (`kubectl apply -n argocd -f install.yaml`,
  `helm install ... -n argocd`, etc.).
- Al ejecutar `kubectl apply -f install.yaml` sin `-n argocd` (y con el contexto
  del kubeconfig sin namespace), kubectl coloca todos los objetos namespaced en
  el namespace del contexto (`default`). El selector con el que luego Argo CD
  localiza sus `Application` (su propio namespace) queda desalineado con lo que
  declara `cluster/base/argocd/app-of-apps.yaml` (`namespace: argocd`).
- El remote base de kustomize era la estrategia de "pin" original, pero además
  del problema de namespace, kustomize v5 deprecó la resolución remota y el
  bootstrap no podía verificarse contra un artefacto local.

## Fix aplicado

1. **Manifest vendored**: se descargó el `install.yaml` oficial de v2.12.3
   (1.253.454 bytes) y se copió como
   `cluster/base/argocd/install/install.yaml` (inmutable, pinned por SHA-256 en
   el comentario del kustomization).
2. **Kustomization reescrito sin remote base**:
   `cluster/base/argocd/install/kustomization.yaml` ahora declara
   `resources: [install.yaml]` (archivo local) y mantiene el patch JSON6902 que
   agrega `--insecure` al `argocd-server` para exponer la UI por HTTP plano
   (sin warning de certificado autofirmado). Comando canónico:

   ```bash
   kubectl apply -k cluster/base/argocd/install -n argocd
   ```

   Equivalente sin kustomize (mismo resultado, sin el patch `--insecure`):

   ```bash
   kubectl apply -n argocd -f cluster/base/argocd/install/install.yaml
   ```

3. **Remediación del clúster local**: se eliminaron todos los objetos de ArgoCD
   que habían quedado en `default` (deployments, statefulset, services,
   configmaps, secrets, serviceaccounts, roles, rolebindings y networkpolicies;
   los `ClusterRole`/`ClusterRoleBinding`/CRD cluster-scoped se conservaron, ya
   que sus `subjects` apuntan a `argocd`) y se re-aplicó el manifest con
   `kubectl apply -n argocd -f <install.yaml>`.
4. **Acceso durable a la UI** (documentado como convención local):
   - `argocd-server` expuesto como `LoadBalancer` (el servicelb de k3s asigna
     la IP del nodo: `http://<node-ip>:443`), y
   - port-forward persistente para el host Windows:
     `kubectl port-forward -n argocd svc/argocd-server 8080:443` →
     `http://localhost:8080` (lanzado con `Start-Process` para que sobreviva
     entre invocaciones de shell).

Archivos afectados:

- `cluster/base/argocd/install/install.yaml` (nuevo, vendored)
- `cluster/base/argocd/install/kustomization.yaml` (reescrito: sin remote base)
- `docs/fixes/22-argocd-local-bootstrap.md` (este documento)

## Cómo verificar

**Instalación (clúster local):**

```bash
kubectl --kubeconfig $env:USERPROFILE\.kube\floci-ecommerce.yaml get pods -n argocd
kubectl --kubeconfig $env:USERPROFILE\.kube\floci-ecommerce.yaml get svc -n argocd argocd-server
```

- Los 7 pods de ArgoCD deben estar `1/1 Running` en el namespace `argocd`
  (y ningún pod `argocd-*` en `default`).
- `argocd-server` debe tener `EXTERNAL-IP` asignada (IP del nodo k3s).
- `kubectl wait --for=condition=available deployment/argocd-server -n argocd
  --timeout=180s` debe completar sin error.

**Bootstrap reproducible (kustomize v5):**

```bash
kubectl kustomize cluster/base/argocd/install | findstr /C:"github.com/argoproj" /C:"namespace: argocd"
```

- El render no debe referenciar ninguna URL remota (`github.com/argoproj` solo
  aparece en `subjects`... en realidad no debe aparecer como source remota) y
  debe contener los objetos de ArgoCD.
- En un clúster limpio, `kubectl apply -k cluster/base/argocd/install -n argocd`
  instala ArgoCD completo en `argocd`.

**UI:**

- Abrir `http://localhost:8080` (port-forward activo) o
  `http://<node-ip>:443` (LoadBalancer): debe cargar la página de login de
  Argo CD.
- Credencial inicial: `kubectl get secret argocd-initial-admin-secret -n argocd
  -o jsonpath="{.data.password}" | base64 -d` (usuario `admin`).