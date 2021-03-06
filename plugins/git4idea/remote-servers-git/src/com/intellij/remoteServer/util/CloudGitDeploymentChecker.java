/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.remoteServer.util;

import com.intellij.execution.configurations.RuntimeConfigurationWarning;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.remoteServer.agent.util.CloudGitApplication;
import com.intellij.remoteServer.configuration.RemoteServer;
import com.intellij.remoteServer.configuration.ServerConfigurationBase;
import com.intellij.remoteServer.configuration.deployment.DeploymentSource;
import com.intellij.remoteServer.configuration.deployment.ModuleDeploymentSource;
import git4idea.GitUtil;
import git4idea.commands.Git;
import git4idea.repo.GitRemote;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author michael.golubev
 */
public class CloudGitDeploymentChecker<
  T extends CloudDeploymentNameConfiguration,
  SC extends ServerConfigurationBase,
  SR extends CloudMultiSourceServerRuntimeInstance<T, ?, ?, ?>> {

  private GitRepositoryManager myGitRepositoryManager;
  private Git myGit;

  private final DeploymentSource myDeploymentSource;
  private final RemoteServer<SC> myServer;
  private final CloudDeploymentNameEditor<T> mySettingsEditor;

  public CloudGitDeploymentChecker(DeploymentSource deploymentSource,
                                   RemoteServer<SC> server,
                                   CloudDeploymentNameEditor<T> settingsEditor) {
    myDeploymentSource = deploymentSource;
    myServer = server;
    mySettingsEditor = settingsEditor;
  }

  public void checkGitUrl(final T settings, Pattern gitUrlPattern) throws ConfigurationException {
    if (!(myDeploymentSource instanceof ModuleDeploymentSource)) {
      return;
    }

    ModuleDeploymentSource moduleSource = (ModuleDeploymentSource)myDeploymentSource;
    Module module = moduleSource.getModule();
    if (module == null) {
      return;
    }

    File contentRootFile = myDeploymentSource.getFile();
    if (contentRootFile == null) {
      return;
    }

    final Project project = module.getProject();

    if (myGitRepositoryManager == null) {
      myGitRepositoryManager = GitUtil.getRepositoryManager(project);
    }
    if (myGit == null) {
      myGit = ServiceManager.getService(Git.class);
      if (myGit == null) {
        return;
      }
    }

    VirtualFile contentRoot = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(contentRootFile);
    if (contentRoot == null) {
      return;
    }

    GitRepository repository = myGitRepositoryManager.getRepositoryForRoot(contentRoot);
    if (repository == null) {
      return;
    }


    String expectedName = settings.getDeploymentSourceName(myDeploymentSource);

    boolean unexpectedNameFound = false;
    for (GitRemote remote : repository.getRemotes()) {
      for (String url : remote.getUrls()) {
        Matcher matcher = gitUrlPattern.matcher(url);
        if (matcher.matches()) {
          String matchedName = matcher.group(1);
          if (matchedName.equals(expectedName)) {
            return;
          }
          else {
            unexpectedNameFound = true;
            break;
          }
        }
      }
    }

    if (!unexpectedNameFound) {
      return;
    }


    RuntimeConfigurationWarning warning =
      new RuntimeConfigurationWarning("Cloud Git URL found in repository, but it doesn't match the run configuration");

    warning.setQuickFix(new Runnable() {

      @Override
      public void run() {
        CloudGitApplication application
          = new CloudConnectionTask<CloudGitApplication, SC, T, SR>(project, "Searching for application", true, true) {

          @Override
          protected RemoteServer<SC> getServer() {
            return myServer;
          }

          @Override
          protected CloudGitApplication run(SR serverRuntime) throws ServerRuntimeException {
            CloudGitDeploymentRuntime deploymentRuntime
              = (CloudGitDeploymentRuntime)serverRuntime.createDeploymentRuntime(myDeploymentSource, settings, project);
            return deploymentRuntime.findApplication4Repository();
          }
        }.perform();

        if (application == null) {
          Messages.showErrorDialog(mySettingsEditor.getComponent(), "No application matching repository URL(s) found in account");
        }
        else {
          T fixedSettings = mySettingsEditor.getFactory().create();
          fixedSettings.setDefaultDeploymentName(false);
          fixedSettings.setDeploymentName(application.getName());
          mySettingsEditor.resetFrom(fixedSettings);
        }
      }
    });

    throw warning;
  }
}
