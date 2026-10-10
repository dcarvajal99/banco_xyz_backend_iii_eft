# Despliegue del sistema en AWS

Banco XYZ · EFT Desarrollo Backend III (PBY2203) · Diego Carvajal

Las diez aplicaciones ya están preparadas para la nube: cada una es una imagen Docker independiente, toma toda su
configuración del Config Server o de variables de entorno (perfil `nube`), se registra en Eureka con el id de su
contenedor, escucha en todas las interfaces y expone su salud en un puerto de operación. Este documento describe dos
caminos:

- **Opción A — una instancia EC2 con Docker Compose.** El mismo `docker-compose.yaml` que se usa en local, más un
  override con el nombre público. Es el camino más corto para una demostración o un ambiente de pruebas.
- **Opción B — Amazon ECS (Fargate) con servicios administrados.** La arquitectura objetivo de producción: RDS, MSK,
  balanceadores con certificados de ACM, secretos en Secrets Manager y escalado automático.

> Las evidencias del informe se tomaron con el mismo compose en un equipo local (Docker sobre Colima). Los pasos de
> este documento usan la AWS CLI v2 y no requieren cambios en el código.

## 1. Qué se despliega

| Contenedor del compose | Imagen | Opción A (EC2) | Opción B (ECS) |
|---|---|---|---|
| `bff-web`, `bff-movil` | `banco-xyz/bff-web`, `banco-xyz/bff-movil` | Contenedor, puerto publicado | Servicio Fargate detrás de un **ALB** (HTTPS con certificado de ACM) |
| `bff-cajero` | `banco-xyz/bff-cajero` | Contenedor, puerto publicado | Servicio Fargate detrás de un **NLB** en modo TCP (el TLS mutuo con el terminal termina en el contenedor) |
| `banco-auth` | `banco-xyz/banco-auth` | Contenedor, puerto publicado | Servicio Fargate detrás del ALB (`auth.<dominio>`) |
| `clientes-service`, `cuentas-service`, `pagos-service` | una imagen cada uno | Contenedores internos (`--scale`) | Servicios Fargate en subredes privadas, 2 tareas mínimo, autoescalado por CPU |
| `config-server`, `eureka-1`, `eureka-2` | `banco-xyz/config-server`, `banco-xyz/eureka-server` | Contenedores internos | Servicios Fargate privados con nombre en **Cloud Map** (`config-server.banco.local`, `eureka-1.banco.local`…) |
| `banco-xyz-batch` | `banco-xyz/banco-xyz-batch` | Contenedor de una ejecución | **Tarea programada** de ECS con EventBridge Scheduler (cierre nocturno) |
| `db` (PostgreSQL 17) | `postgres:17-alpine` | Contenedor con volumen | **Amazon RDS for PostgreSQL 17**, Multi-AZ |
| `kafka` (3.9.1 KRaft) | `apache/kafka:3.9.1` | Contenedor con volumen | **Amazon MSK** (3 brokers en 3 zonas, factor de replicación 3) |
| `pki` | `banco-xyz/pki` | Contenedor de una ejecución | **AWS Private CA** emite los certificados de servicio; se montan desde Secrets Manager o EFS |
| `.env` | — | Archivo en la instancia, generado desde Secrets Manager | **Secrets Manager** referenciado en cada task definition (`secrets`) |
| Logs y métricas | — | `docker compose logs` | **CloudWatch Logs** (driver `awslogs`) y `/actuator/prometheus` hacia Amazon Managed Service for Prometheus |

## 2. Opción A: EC2 con Docker Compose

### 2.1 Red y permisos

1. Una VPC con una subred pública (o la VPC por defecto).
2. Un **Security Group** `banco-xyz-ec2`:

   | Puerto | Origen | Para qué |
   |---|---|---|
   | 22 | Solo la IP del administrador | SSH (y túnel a las consolas de operación) |
   | 8090, 8091, 8081 | Clientes autorizados (o `0.0.0.0/0` en una demo) | Banca web, app móvil y login |
   | 8083 | Rango de la red de cajeros | BFF de cajeros (además exige certificado de terminal) |

3. Un rol de instancia con `AmazonSSMManagedInstanceCore` y permiso `secretsmanager:GetSecretValue` sobre el secreto
   `banco-xyz/env` (paso 2.3).

### 2.2 Instancia

Amazon Linux 2023, tipo **t3.xlarge** (4 vCPU, 16 GB; el ecosistema completo usa cerca de 6 GB y deja margen para
`--scale`), disco gp3 de 40 GB.

```bash
aws ec2 run-instances --image-id resolve:ssm:/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
  --instance-type t3.xlarge --key-name banco-xyz --security-group-ids sg-XXXX --subnet-id subnet-XXXX \
  --iam-instance-profile Name=banco-xyz-ec2 \
  --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=40,VolumeType=gp3}' \
  --tag-specifications 'ResourceType=instance,Tags=[{Key=Name,Value=banco-xyz}]'
```

Dentro de la instancia (SSH o Session Manager):

```bash
sudo dnf install -y docker git
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user && newgrp docker
sudo mkdir -p /usr/local/lib/docker/cli-plugins
sudo curl -sSL https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose && sudo chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version
```

### 2.3 Código y secretos

Los secretos no viajan en el repositorio. Se guardan una vez en Secrets Manager (desde el equipo del administrador) con
el contenido del `.env`:

```bash
aws secretsmanager create-secret --name banco-xyz/env --secret-string file://.env
```

En la instancia:

```bash
git clone https://github.com/dcarvajal99/banco_xyz_backend_iii_eft.git banco-xyz && cd banco-xyz
aws secretsmanager get-secret-value --secret-id banco-xyz/env --query SecretString --output text > .env
chmod 600 .env
```

En un ambiente real se cambian **todos** los valores `*-dev` de `.env.ejemplo` por valores generados
(`openssl rand -base64 32`).

### 2.4 Levantar

```bash
export BANCO_HOST_PUBLICO=$(curl -s http://169.254.169.254/latest/meta-data/public-hostname)
docker compose -f docker-compose.yaml -f docker-compose.aws.yaml up -d --build --wait
docker compose ps
```

`docker-compose.aws.yaml` cambia solo lo que depende de la red: publica la entrada de cada canal y el login en todas las
interfaces (el Security Group decide quién llega) y usa el nombre público para el login del navegador (la
`authorization-uri` de bff-web y la URL de retorno registrada en banco-auth). Los microservicios, la base, Kafka, el
Config Server y Eureka siguen sin exponerse.

### 2.5 Verificar

```bash
# desde la instancia: las evidencias corren igual que en local
infra/scripts/ecosistema.sh
infra/scripts/bff_canales.sh
# desde el equipo: consolas de operación por túnel SSH
ssh -i banco-xyz.pem -L 8761:127.0.0.1:8761 -L 8888:127.0.0.1:8888 ec2-user@$BANCO_HOST_PUBLICO
```

Navegador: `https://<nombre público>:8090`. Con la CA de desarrollo el navegador advierte del certificado (el nombre
público no está en el certificado); para un uso real se pone un ALB con certificado de ACM delante (opción B).

### 2.6 Escalar y actualizar

```bash
docker compose -f docker-compose.yaml -f docker-compose.aws.yaml up -d --no-deps \
  --scale cuentas-service=3 --scale pagos-service=2 cuentas-service pagos-service
git pull && docker compose -f docker-compose.yaml -f docker-compose.aws.yaml up -d --build --wait   # nueva versión
```

## 3. Opción B: Amazon ECS Fargate (producción)

### 3.1 Imágenes en Amazon ECR

```bash
REGION=us-east-1
CUENTA=$(aws sts get-caller-identity --query Account --output text)
REGISTRO=$CUENTA.dkr.ecr.$REGION.amazonaws.com
aws ecr get-login-password --region $REGION | docker login --username AWS --password-stdin $REGISTRO
for app in bff-web bff-movil bff-cajero clientes-service cuentas-service pagos-service config-server eureka-server \
           banco-auth banco-xyz-batch; do
  aws ecr describe-repositories --repository-names banco-xyz/$app > /dev/null 2>&1 \
    || aws ecr create-repository --repository-name banco-xyz/$app --image-scanning-configuration scanOnPush=true
  docker build --platform linux/amd64 -t $REGISTRO/banco-xyz/$app:1.0.0 $app
  docker push $REGISTRO/banco-xyz/$app:1.0.0
done
```

Cada aplicación se construye y publica por separado: un cambio en `pagos-service` solo reconstruye y despliega esa
imagen. En un pipeline (GitHub Actions o CodePipeline) este bucle se divide en un job por carpeta.

### 3.2 Red

- VPC en **dos zonas de disponibilidad**: subredes públicas (ALB, NLB, NAT Gateway) y privadas (tareas, RDS, MSK).
- Security Groups por capa: el ALB/NLB solo llega a los BFF y a banco-auth; los BFF solo a los microservicios; los
  microservicios a RDS (5432) y MSK (9092/9094). Nada de la capa privada tiene IP pública.
- Un namespace privado de **Cloud Map** `banco.local` para los nombres internos (config-server, eureka-1, eureka-2,
  banco-auth).

### 3.3 Datos y mensajería administrados

```bash
aws rds create-db-instance --db-instance-identifier banco-xyz --engine postgres --engine-version 17 \
  --db-instance-class db.t4g.medium --allocated-storage 50 --multi-az --master-username banco \
  --manage-master-user-password --db-name banco_xyz --vpc-security-group-ids sg-RDS --db-subnet-group-name banco-privadas
aws kafka create-cluster-v2 --cluster-name banco-xyz --provisioned file://msk.json   # 3 brokers kafka.t3.small, 3 AZ
```

- Cada servicio crea **su propio esquema** con Flyway al arrancar (clientes, cuentas, pagos); el batch crea `migracion`.
- **Conexiones:** cada tarea abre su pool (máximo 15 a 20, 2 en reposo). Con el autoescalado al máximo (6 tareas de cada
  microservicio) se necesitan hasta ~300 conexiones: se ajusta `max_connections` en el grupo de parámetros de RDS o se
  pone **RDS Proxy** delante. En el compose la base acepta 200 (tres réplicas de cada servicio).
  Lo ideal es un usuario de base por servicio con permisos solo sobre su esquema.
- En MSK los tópicos se crean con **3 particiones y factor de replicación 3** (en el compose es 1 porque hay un solo
  broker): `banco.kafka.particiones` y la replicación se ajustan en `application-nube.properties` del Config Server.
- Variables por servicio: `SPRING_DATASOURCE_URL=jdbc:postgresql://<endpoint-rds>:5432/banco_xyz` y
  `SPRING_KAFKA_BOOTSTRAP_SERVERS=<brokers de MSK>`.

### 3.4 Secretos y certificados

- **Secrets Manager**: un secreto por valor del `.env` (claves de los clientes OAuth, Config Server, base, pepper de
  tarjetas, GitHub). La task definition los entrega como variables de entorno con `secrets`, sin escribirlos en disco.
- **Certificados de servicio**: AWS Private CA emite el certificado de cada servicio (CN = nombre del servicio, como la
  PKI de desarrollo). Se guardan en Secrets Manager y un contenedor *init* los escribe en un volumen efímero
  `/certificados` de la tarea; alternativa más simple: EFS montado solo lectura.
- **Certificados públicos**: ACM en el ALB (`banco.<dominio>`, `auth.<dominio>`). bff-cajero queda detrás de un NLB en
  TCP para que el certificado del terminal llegue al contenedor.

### 3.5 Servicios de ECS

Una task definition por aplicación (Fargate, `awsvpc`), con estos valores comunes:

```json
{
  "family": "cuentas-service",
  "cpu": "512", "memory": "1024",
  "containerDefinitions": [{
    "name": "cuentas-service",
    "image": "<registro>/banco-xyz/cuentas-service:1.0.0",
    "portMappings": [{"containerPort": 8100}, {"containerPort": 9100}],
    "environment": [
      {"name": "SPRING_PROFILES_ACTIVE", "value": "tls,nube"},
      {"name": "BANCO_CONFIG_SERVER", "value": "https://config-server.banco.local:8888"},
      {"name": "BANCO_CONFIG_FAIL_FAST", "value": "true"}
    ],
    "secrets": [
      {"name": "BANCO_DB_CLAVE", "valueFrom": "arn:aws:secretsmanager:...:banco-xyz/db-clave"},
      {"name": "BANCO_OAUTH_CUENTAS_SECRETO", "valueFrom": "arn:aws:secretsmanager:...:banco-xyz/oauth-cuentas"}
    ],
    "healthCheck": {"command": ["CMD-SHELL", "wget -qO- http://127.0.0.1:9100/actuator/health | grep -q '^{\"status\":\"UP\"'"],
                    "startPeriod": 120},
    "logConfiguration": {"logDriver": "awslogs", "options": {"awslogs-group": "/banco-xyz/cuentas-service",
                         "awslogs-region": "us-east-1", "awslogs-stream-prefix": "ecs"}}
  }]
}
```

| Servicio de ECS | Tareas | Balanceador | Escalado |
|---|---|---|---|
| config-server | 2 | — (Cloud Map) | fijo |
| eureka-1, eureka-2 | 1 + 1 | — (Cloud Map) | fijo (dos peers) |
| banco-auth | 2 | ALB `auth.<dominio>` | CPU 60 % |
| clientes-service, cuentas-service, pagos-service | 2 a 6 | — (Eureka + LoadBalancer de los BFF) | CPU 60 % y memoria 75 % |
| bff-web, bff-movil | 2 a 6 | ALB (`/` y `/movil/*`) | CPU 60 % y solicitudes por destino |
| bff-cajero | 2 a 4 | NLB TCP 443 | CPU 60 % |

```bash
aws ecs create-service --cluster banco-xyz --service-name cuentas-service --task-definition cuentas-service \
  --desired-count 2 --launch-type FARGATE \
  --network-configuration 'awsvpcConfiguration={subnets=[subnet-privada-a,subnet-privada-b],securityGroups=[sg-servicios]}'
aws application-autoscaling register-scalable-target --service-namespace ecs \
  --resource-id service/banco-xyz/cuentas-service --scalable-dimension ecs:service:DesiredCount --min-capacity 2 --max-capacity 6
aws application-autoscaling put-scaling-policy --service-namespace ecs --resource-id service/banco-xyz/cuentas-service \
  --scalable-dimension ecs:service:DesiredCount --policy-name cpu60 --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration \
  '{"TargetValue":60,"PredefinedMetricSpecification":{"PredefinedMetricType":"ECSServiceAverageCPUUtilization"}}'
```

Escalar no requiere cambios: cada tarea se registra en Eureka con su propio id (`HOSTNAME` de la tarea) y su IP
(`spring.cloud.client.ip-address`), los BFF reparten con Spring Cloud LoadBalancer y Kafka reparte las particiones
entre las tareas del mismo grupo de consumidores. El outbox usa `for update skip locked`, así que varias réplicas
publican sin duplicar.

### 3.6 Batch programado

```bash
aws scheduler create-schedule --name banco-xyz-cierre-nocturno --schedule-expression 'cron(30 2 * * ? *)' \
  --flexible-time-window Mode=OFF --target '{
    "Arn": "arn:aws:ecs:us-east-1:<cuenta>:cluster/banco-xyz",
    "RoleArn": "arn:aws:iam::<cuenta>:role/banco-xyz-scheduler",
    "EcsParameters": {"TaskDefinitionArn": "arn:aws:ecs:...:task-definition/banco-xyz-batch", "LaunchType": "FARGATE",
      "NetworkConfiguration": {"awsvpcConfiguration": {"Subnets": ["subnet-privada-a"], "SecurityGroups": ["sg-batch"]}}}}'
```

El contenedor termina con código 0 (bien), 1 (fallo técnico, ya reejecutado dos veces por el lanzador) o 3 (revisión
manual). Una regla de EventBridge sobre `ECS Task State Change` con `exitCode != 0` avisa al área de datos por SNS.

### 3.7 Observabilidad

- **Logs**: cada línea lleva el id de correlación (`X-Correlacion-Id`) que el BFF genera y los servicios propagan;
  en CloudWatch Logs Insights se sigue una operación de punta a punta.
- **Métricas**: `/actuator/prometheus` en el puerto de operación de cada servicio (estado de los circuitos, reintentos,
  latencias HTTP, consumidores de Kafka). Se recogen con Amazon Managed Service for Prometheus y se grafican en
  Amazon Managed Grafana.
- **Alarmas**: circuito abierto más de 1 minuto, LAG de un grupo de Kafka creciendo, tareas que reinician, batch con
  código distinto de 0.

## 4. Lista de verificación

- [ ] Los valores de `.env.ejemplo` terminados en `-dev` reemplazados por secretos generados.
- [ ] Ningún microservicio, base o broker con IP pública ni regla de entrada desde Internet.
- [ ] `docker compose ps` (A) o `aws ecs describe-services` (B): todo sano; el batch terminó con código 0.
- [ ] Eureka muestra los siete servicios (y las réplicas al escalar).
- [ ] `infra/scripts/probar_todo.sh` termina con 0 fallas contra el ambiente desplegado.
