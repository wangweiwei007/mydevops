# 基于 K3s + ArgoCD 的 Java GitOps 平台实战
## 1.Java 应用
### 创建Springboot项目
使用Spring Initializr创建一个Springboot项目，选择Web依赖，生成项目后导入IDE。
### 编写代码
在`src/main/java/com/example/demo/DemoApplication.java`中编写一个简单的REST接口：
```java
package com.example.demo;       
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;      
@SpringBootApplication  
@RestController 
public class DemoApplication {      
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }      
    @GetMapping("/hello")
    public String hello() {
        return "Hello, DevOps!";
    }
}
``` 
### 配置Jib插件
> Jib插件介绍：Jib是一个用于Java应用的容器化工具，可以直接将Java应用打包为Docker镜像，而无需编写Dockerfile。它支持Maven和Gradle，并且可以与各种容器注册表（如Docker Hub、Google Container Registry、Azure Container Registry等）集成。

在`pom.xml`中添加Jib插件配置，用于将应用打包为Docker镜像并推送到ACR：
```xml
<artifactId>mydevops</artifactId>
<properties>
    <jib-maven-plugin.version>3.1.4</jib-maven-plugin.version>
    <docker.repo>registry.cn-hangzhou.aliyuncs.com/my-devops-ns</docker.repo>
    <image.tag>0.0.1-SNAPSHOT</image.tag>
</properties>
<!-- pom.xml 中的 Jib 插件配置 -->
<build>
    <plugins>
        <plugin>
            <groupId>com.google.cloud.tools</groupId>
            <artifactId>jib-maven-plugin</artifactId>
            <version>${jib-maven-plugin.version}</version>
            <configuration>
                <to>
                    <image>${docker.repo}/${project.artifactId}:${image.tag}</image>
                    <auth>
                        <username>${env.DOCKER_USERNAME}</username>
                        <password>${env.DOCKER_PASSWORD}</password>
                    </auth>
                </to>
            </configuration>
        </plugin>
    </plugins>
</build>
``` 
> - 请将`<docker.repo>`中的`my-devops-ns`替换为您在ACR中创建的命名空间名称。  
> - 请将`${project.artifactId}`替换为您在ACR中创建的镜像仓库名称(my-devops-rep)。  
> - 确保在GitHub Actions中正确设置了`DOCKER_USERNAME`和`DOCKER_PASSWORD`环境变量，以便Jib插件能够成功认证并推送镜像到ACR。

> Jib 的默认行为：在 pom.xml 中，使用了以下配置来构造镜像路径：
>```xml
> <image>${docker.repo}/${project.artifactId}:${image.tag}</image>
>```
> - 镜像名称默认为 Maven 项目的 Artifact ID（即 `mydevops`）。
> - 这与我们在 ACR 中创建的镜像仓库名称（`my-devops-rep`）不匹配。
>   - 因此，我们需要在 Jib 配置中显式指定完整的镜像路径。  
>   - 这就是为什么我们在 `<to><image>` 标签中使用了 `${docker.repo}/my-devops-rep:${image.tag}`。
>   - 这样可以确保 Jib 构建的镜像名称与 ACR 中的仓库名称一致，避免推送失败的问题。
>   - 此外，我们还添加了 `<auth>` 块，强制 Jib 使用环境变量中的凭据进行认证，以解决在 GitHub Actions 环境中可能出现的认证失败问题。
>   - 通过这种方式，我们确保了 Jib 能够正确地将镜像推送到指定的 ACR 仓库中。
## 2.准备K3s环境
在一台Linux服务器上安装K3s：
```bash
curl -sfL https://get.k3s.io | sh -
```
验证安装：
```bash
kubectl get nodes
```
## 3.安装ArgoCD
在K3s集群中安装ArgoCD：
```bashkubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```
修改`argocd-server`服务类型为`NodePort`，以便从宿主机访问：
```bash
kubectl -n argocd edit svc argocd-server
```
将`type: ClusterIP`改为`type: NodePort`，保存退出。
获取NodePort端口：
```bash
kubectl -n argocd get svc argocd-server
```
使用宿主机IP和NodePort访问ArgoCD UI，默认用户名为`admin`，密码为安装时生成的初始密码：
```bash
kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath="{.data.password}" | base64 -d; echo
```
## 4.配置GitHub Actions
### 前期准备工作：GitHub Secrets
您需要在您的应用代码仓库 的 Settings > Security > Secrets and variables > Actions 中添加以下三个密钥：
在GitHub仓库中添加以下Secrets：
- `ACR_USERNAME`：阿里云ACR的用户名
- `ACR_PASSWORD`：阿里云ACR的密码
- `CONFIG_REPO_PAT`：用于访问K8s配置仓库的Personal Access Token
> 以下是申请GitHub访问令牌（Access Token）的步骤：
> 1. 登录GitHub，点击右上角头像，选择`Settings`。
> 2. 在左侧菜单中选择`Developer settings`，然后选择`Personal access tokens`。
> 3. 点击`Generate new token`按钮，选择适当的权限（如`repo`权限），然后生成令牌。
> 4. 复制生成的令牌，并将其添加到您的应用代码仓库的Secrets中，命名为`CONFIG_REPO_PAT`。  
>   - **注意**：确保`CONFIG_REPO_PAT`具有访问`k8s-config-repo`仓库的权限，通常需要勾选`repo`权限。
###  创建K8s配置仓库
创建一个新的GitHub仓库（例如`k8s-config-repo`），用于存放Kubernetes部署配置文件。在该仓库中创建一个`deployment.yaml`文件，内容如下：
```yamlapiVersion: apps/v1
# deployment.yaml (用于 k8s-config-repo)
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mydevops-app-deployment
  labels:
    app: mydevops
spec:
  # 您希望运行的 Pod 副本数量
  replicas: 2
  selector:
    matchLabels:
      app: mydevops
  template:
    metadata:
      labels:
        app: mydevops
    spec:
      containers:
      - name: mydevops-container
        # ✨ 关键点：这是 CI 脚本 (Step 7) 将会替换的字段！
        # 初始标签可以使用最新的稳定版本或 'latest'
        image: registry.cn-hangzhou.aliyuncs.com/my-devops-ns/my-devops-rep:0.0.1-SNAPSHOT

        ports:
        - containerPort: 8080
          name: http-web

        # 容器资源限制：限制 Java 进程的 CPU 和内存，防止资源耗尽
        resources:
          limits:
            memory: "1024Mi"
            cpu: "500m"
          requests:
            memory: "512Mi"
            cpu: "250m"

        # 优化：配置健康检查 (Liveness/Readiness Probes)
        # 确保 Kubernetes 不会向未准备好的 Pod 发送流量，并自动重启不健康的 Pod。
        # 如果您的 Spring Boot 应用使用了 Actuator，可以直接用 /actuator/health
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 120 # 启动时间长，延迟启动检查
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10

---
# Service YAML (推荐放在同一文件或同目录下)
apiVersion: v1
kind: Service
metadata:
  name: mydevops-service
spec:
  # ClusterIP 类型，供集群内部和 Ingress 访问
  type: ClusterIP
  selector:
    app: mydevops # 必须匹配 Deployment 的 labels
  ports:
    - port: 80 # Service 暴露的端口 (供 Ingress 访问)
      targetPort: 8080 # 转发到容器的 8080 端口
      protocol: TCP

---
# Ingress YAML (推荐放在同一文件或同目录下)
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: mydevops-ingress
  # 确保 k3s 的 Traefik 识别这个 Ingress
  annotations:
    ingress.kubernetes.io/ssl-redirect: "false"
spec:
  rules:
  - http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: mydevops-service # 指向上面定义的 Service
            port:
              number: 80 # Service 的端口
```

### 创建GitHub Actions工作流
在GitHub仓库（即您的Springboot应用仓库）中创建`.github/workflows/ci-cd.yml`文件，配置GitHub Actions工作流：
```yamlname: CI/CD Pipeline
name: CI/CD Pipeline (Java & Jib GitOps)

on:
  push:
    branches:
      - master # 触发主分支
    paths-ignore:
      - 'k8s/**' # 忽略配置文件的提交

jobs:
  build_and_deploy:
    runs-on: ubuntu-latest

    steps:
      - name: 1. 检出应用代码
        uses: actions/checkout@v4

      - name: 2. 设置 Java 环境 (JDK 17)
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
#           cache: 'maven'  # 暂时注释掉这一行

      - name: 3. 构建、测试 Java 应用
        run: mvn clean verify

      - name: 4. 定义镜像标签和仓库变量
        id: set_vars
        run: |
          # 变量 1：使用 Commit SHA 作为唯一的镜像标签
          echo "IMAGE_TAG=${{ github.sha }}" >> $GITHUB_ENV

          # 变量 2：阿里云 ACR 域名和命名空间 (不包含镜像仓库名称)
          echo "ACR_REPO=registry.cn-hangzhou.aliyuncs.com/my-devops-ns" >> $GITHUB_ENV

          # 变量 3：固定的镜像仓库名称 (用于 Step 7 的 sed 替换逻辑)
          echo "IMAGE_NAME=my-devops-rep" >> $GITHUB_ENV

      - name: 5. 使用 Jib 构建并推送镜像到 ACR
        env:
          # 传递 ACR 认证凭证给 Jib
          DOCKER_USERNAME: ${{ secrets.ACR_USERNAME }}
          DOCKER_PASSWORD: ${{ secrets.ACR_PASSWORD }}
        run: |
          echo "开始构建镜像: ${{ env.ACR_REPO }}/${{ env.IMAGE_NAME }}:${{ env.IMAGE_TAG }}"

          # 传递 Jib 所需的变量：docker.repo (ACR 路径) 和 image.tag (Commit SHA)
          mvn compile jib:build \
            -Ddocker.repo=${{ env.ACR_REPO }} \
            -Dimage.tag=${{ env.IMAGE_TAG }} \
            -DsendCredentialsOverHttp=true

      - name: 6. GitOps 检出 K8s 配置仓库 (k8s-config-repo)
        uses: actions/checkout@v4
        with:
          repository: wangweiwei007/k8s-config-repo
          token: ${{ secrets.CONFIG_REPO_PAT }}
          path: k8s-config-repo

      - name: 7. GitOps 更新 K8s Deployment YAML (最关键的 GitOps 步骤)
        id: update_yaml
        run: |
          CONFIG_FILE="k8s-config-repo/deployment.yaml"

          # 使用 sed 替换逻辑：
          # 找到 "my-devops-rep:" (IMAGE_NAME) 后面的所有内容，替换为新的 SHA 标签。
          sed -i "s|${{ env.IMAGE_NAME }}:.*|${{ env.IMAGE_NAME }}:${{ env.IMAGE_TAG }}|g" ${CONFIG_FILE}

          echo "Deployment YAML 已更新为新的镜像标签: ${{ env.IMAGE_TAG }}"

      - name: 8. GitOps 提交并推送配置更改
        working-directory: ./k8s-config-repo
        run: |
          git config user.name github-actions
          git config user.email github-actions@github.com

          if ! git diff --quiet ; then
            git add .
            git commit -m "chore(ci): Deploy new image ${{ env.IMAGE_TAG }} for ${{ github.sha }}"
            git push
          else
            echo "No changes in K8s config. Skip commit."
          fi
```     
## 5.配置ArgoCD应用
在ArgoCD UI中创建一个新的应用，配置如下：
- 应用名称：mydevops-app
- 项目：default
  - 源仓库URL：`        
  - 路径：`/`
  - 分支：`master`
- 目标集群：`https://kubernetes.default.svc`
- 目标命名空间：`mydevops-staging`
创建应用后，手动触发同步操作，ArgoCD 会将应用部署到 K3s 集群中。
- - -   
# 总结
这是一个非常棒且完整的 DevOps 实践总结！我们从零开始搭建并解决了五个主要的障碍。

## 🌟 平台总结：基于 K3s + ArgoCD 的 Java GitOps 平台

| 组件 | 角色定位 | 您的具体配置 |
| :--- | :--- | :--- |
| **Java 应用** | 源代码，构建源 | Spring Boot / Maven |
| **Jib 插件** | Docker 镜像构建工具 | 将应用打包为 ACR 镜像 (无需 Dockerfile) |
| **GitHub Actions** | 持续集成 (CI) | 负责构建、推送镜像、更新 GitOps 配置仓库 |
| **ACR** | 容器镜像仓库 | 阿里云镜像服务，存储最终镜像 |
| **K3s** | 容器运行时 (轻量级 K8s) | 目标部署集群 |
| **ArgoCD** | 持续部署 (CD) | 负责监控 Git 仓库，自动同步状态到 K3s (GitOps 引擎) |

---

## 🚧 搭建过程中的主要问题与解决方案

| # | 问题描述 | 根本原因 | 最终解决方案 |
| :--- | :--- | :--- | :--- |
| **1** | **Jib 镜像路径不匹配** | Jib 默认使用 Maven Artifact ID (`mydevops`) 作为镜像名，与阿里云 ACR 仓库名 (`my-devops-rep`) 不一致。 | **修改 `pom.xml`**：在 Jib 配置中，将 `<image>` 路径硬编码为 `<image>${docker.repo}/my-devops-rep:${image.tag}</image>`。 |
| **2** | **ACR 认证失败 (401)** | Jib 在 GitHub Actions 环境中无法正确获取并使用 Secrets 传入的 ACR 登录凭证。 | **强制 Jib 认证**：在 `pom.xml` 的 Jib 配置中，显式添加 `<auth>` 块，强制使用环境变量：`<username>${env.DOCKER_USERNAME}</username>`。 |
| **3** | **ArgoCD 访问失败** | 首次尝试 `kubectl port-forward` 失败，原因是 K3s 宿主机缺少依赖 (`socat`)，且端口转发在虚拟机网络中容易出现绑定问题。 | **NodePort 暴露服务**：1) 在 K3s 宿主机上安装 `socat`。2) 将 `argocd-server` 的 Service 类型从 `ClusterIP` 改为 **`NodePort`** (`type: NodePort`)，通过宿主机 IP + NodePort 访问。 |
| **4** | **ArgoCD 初始同步失败** | ArgoCD 应用配置的目标 Namespace (`mydevops-staging`) 在集群中不存在，导致资源创建失败。 | **手动创建 Namespace**：执行 `kubectl create namespace mydevops-staging`，然后手动触发 ArgoCD 同步。 |
| **5** | **Git 访问超时** | `argocd-repo-server` 访问 GitHub 仓库时出现 `context deadline exceeded` 错误，根本原因是 K3s 内部的 **CoreDNS 服务不稳定**（有 5 次重启记录），导致域名解析失败或超时。 | **DNS 绕过 (HostAliases)**：编辑 `argocd-repo-server` Deployment，添加 **`hostAliases`** 配置，将 `github.com` 映射到固定的 IP 地址，绕过不稳定的 CoreDNS 解析。 |

---

## 🎉 最终成果

通过解决上述问题，您最终成功打通了整个 CI/CD 闭环：

* **代码提交** ➡️ **GitHub Actions (构建/推送到 ACR)** ➡️ **自动更新配置仓库** ➡️ **ArgoCD 自动检测** ➡️ **K3s 集群自动部署** ➡️ **应用在浏览器中可访问。**