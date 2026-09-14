# Instalación

## Docker

Floci-AZ se distribuye como una imagen Docker multiarquitectura (`linux/amd64` y `linux/arm64`).

### Etiquetas de imagen

| Etiqueta | Descripción |
|---|---|
| `latest` | Binario nativo — arranque en **<100ms** **(recomendado)** |
| `latest-jvm` | Imagen JVM — más grande, sin necesidad de GraalVM |
| `x.y.z` | Versión nativa fijada |
| `x.y.z-jvm` | Versión JVM fijada |
| `edge` | Compilación semanal desde `main` |

### Ejecución rápida

```bash
docker run -d --name floci-az \
  -p 4577:4577 \
  -v ./data:/app/data \
  -v /var/run/docker.sock:/var/run/docker.sock \
  floci/floci-az:latest
```

El montaje del socket de Docker es necesario para Azure Functions. El entrypoint maneja
automáticamente los permisos de grupo del socket de Docker tanto en hosts Linux como en macOS/Windows.

---

## Compilación manual (desarrollo)

Requisitos: **Java 25**, **Maven 3.9+**, **Docker**

### Compilación JVM

```bash
./mvnw package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar
```

### Compilación nativa (arranque más rápido)

```bash
./mvnw package -Dnative -DskipTests
./target/*-runner
```

Las compilaciones nativas requieren GraalVM / Mandrel con soporte de native-image.

### Compilación Docker (local)

```bash
# JVM image
docker build -f Dockerfile -t floci-az:dev .

# Native image (single-arch, current machine)
docker build -f Dockerfile.native -t floci-az:dev-native .
```