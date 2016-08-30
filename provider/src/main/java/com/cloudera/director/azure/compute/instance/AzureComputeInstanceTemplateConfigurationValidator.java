/*
 * Copyright (c) 2016 Cloudera, Inc.
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

package com.cloudera.director.azure.compute.instance;

import static com.cloudera.director.azure.Configurations.AZURE_CONFIG_INSTANCE_DNS_LABEL_REGEX;
import static com.cloudera.director.azure.Configurations.AZURE_CONFIG_INSTANCE_FQDN_SUFFIX_REGEX;
import static com.cloudera.director.azure.Configurations.AZURE_CONFIG_INSTANCE_SUPPORTED;
import static com.cloudera.director.spi.v1.compute.ComputeInstanceTemplate.ComputeInstanceTemplateConfigurationPropertyToken.IMAGE;
import static com.cloudera.director.spi.v1.model.util.Validations.addError;

import com.cloudera.director.azure.Configurations;
import com.cloudera.director.azure.compute.credentials.AzureCredentials;
import com.cloudera.director.azure.compute.provider.AzureComputeProviderHelper;
import com.cloudera.director.azure.utils.AzureVmImageInfo;
import com.cloudera.director.spi.v1.model.ConfigurationPropertyToken;
import com.cloudera.director.spi.v1.model.exception.PluginExceptionConditionAccumulator;
import com.microsoft.windowsazure.exception.ServiceException;
import com.typesafe.config.Config;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;



import com.cloudera.director.spi.v1.model.ConfigurationValidator;
import com.cloudera.director.spi.v1.model.Configured;
import com.cloudera.director.spi.v1.model.InstanceTemplate;
import com.cloudera.director.spi.v1.model.LocalizationContext;
import com.typesafe.config.ConfigException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validator for Azure Compute Instance configs. Entry point should be the validate method.
 * Validate method will get a new helper(token).
 */
@SuppressWarnings("PMD.TooManyStaticImports")
public class AzureComputeInstanceTemplateConfigurationValidator implements ConfigurationValidator {

  private static final Logger LOG =
    LoggerFactory.getLogger(AzureComputeInstanceTemplateConfigurationValidator.class);

  private Config pluginConfig;
  private Config configurableImages;
  private AzureCredentials credentials; // Credential to do Azure specific validations

  // The location represents the data center; some resource may be valid in one data center but not
  // in others
  private String location;

  static final String VIRTUAL_MACHINE_MSG =
    "Virtual Machine '%s' is not supported.";
  static final String INSTANCE_NAME_PREFIX_MSG =
    "Instance name prefix '%s' does not satisfy Azure DNS label requirement: %s.";
  static final String FQDN_SUFFIX_MSG =
    "FQDN suffix '%s' does not satisfy Azure DNS name suffix requirement: '%s'.";
  static final String VIRTUAL_NETWORK_RESOURCE_GROUP_MSG =
    "Resource Group '%s' does not exist. Please create the Resource Group or use an existing one.";
  static final String VIRTUAL_NETWORK_MSG =
    "Virtual Network '%s' does not exist within the Resource Group '%s'. Please create the " +
      "Virtual Network or use an existing one.";
  static final String SUBNET_MSG =
    "Subnet '%s' does not exist under the Virtual Network '%s'. Please create the subnet or use " +
      "an existing one.";
  static final String NETWORK_SECURITY_GROUP_RESOURCE_GROUP_MSG =
    "Resource Group '%s' does not exist. Please create the Resource Group or use an existing one.";
  static final String NETWORK_SECURITY_GROUP_MSG =
    "Network Security Group '%s' does not exist within the Resource Group '%s'. Please create " +
      "the Network Security Group or use an existing one.";
  static final String AVAILABILITY_SET_MSG =
    "Availability Set '%s' does not exist. Please create the Availability Set or use an " +
      "existing one.";
  static final String RESOURCE_GROUP_MSG =
    "Resource Group '%s' does not exist. Please create the Resource Group or use an existing one.";
  static final String IMAGE_MISSING_IN_AZURE_MSG =
    "IMAGE '%s' does not exist in Azure. Please verify the input.";
  static final String IMAGE_MISSING_IN_CONFIG_MSG =
    "IMAGE '%s' does not exist in configurable image list. Please verify the input.";
  static final String IMAGE_CFG_MISSING_REQUIRED_FIELD_MSG =
    "IMAGE '%s' config does not have all required fields. Please check plugin config file.";
  static final String COMMUNICATION_MSG =
    "Cannot communicate with Azure due to IOException while validating: '%s'.";
  static final String ILLEGAL_ARGUMENT_EXCEPTION_MSG =
    "IllegalArgumentException occurred while validating: '%s'. " +
      "Please check permissions, existence, spelling, etc.";
  static final String GENERIC_MSG = "Exception occurred during validation";

  /**
   * @param providerSectionPluginConfig "provider" section in the plugin config
   * @param configurableImages          config object containing list of configurable images
   * @param credentials                 credential object to get helper for Azure SDK calls
   * @param location                    deployment location/region
   */
  public AzureComputeInstanceTemplateConfigurationValidator(
    Config providerSectionPluginConfig,
    Config configurableImages,
    AzureCredentials credentials, String location) {
    this.pluginConfig = providerSectionPluginConfig;
    this.configurableImages = configurableImages;
    this.credentials = credentials;
    this.location = location;
  }

  /**
   * Validate template configuration with Azure backend. Get a new helper object per method call
   * to ensure the token held by the helper doesn't expire
   * @param name
   * @param directorConfig      Director config
   * @param accumulator         error accumulator
   * @param localizationContext localization context to extract config
   */
  @Override
  public void validate(String name, Configured directorConfig,
    PluginExceptionConditionAccumulator accumulator, LocalizationContext localizationContext) {

    // Local checks
    checkVMSize(directorConfig, accumulator, localizationContext);
    checkFQDNSuffix(directorConfig, accumulator, localizationContext);
    checkInstancePrefix(directorConfig, accumulator, localizationContext);

    /*
     * Azure backend checks: These checks verifies the resources specified in instance template
     * do exist in Azure. Defensive code to catch all Exceptions thrown from Azure SDK so they
     * can be reported properly.
     */
    try {
      AzureComputeProviderHelper helper = credentials.getComputeProviderHelper();
      checkResourceGroup(directorConfig, accumulator, localizationContext, helper);
      checkVirtualNetworkResourceGroup(directorConfig, accumulator, localizationContext,
        helper);
      checkVirtualNetwork(directorConfig, accumulator, localizationContext, helper);
      checkSubnet(directorConfig, accumulator, localizationContext, helper);
      checkNetworkSecurityGroupResourceGroup(directorConfig, accumulator, localizationContext,
        helper);
      checkNetworkSecurityGroup(directorConfig, accumulator, localizationContext, helper);
      checkAvailabilitySet(directorConfig, accumulator, localizationContext, helper);
      checkVmImage(directorConfig, accumulator, localizationContext, helper);
    } catch (Exception e) {
      LOG.error(GENERIC_MSG, e);

      //use null key to indicate generic error
      ConfigurationPropertyToken token =null;
      addError(accumulator, token, localizationContext, null, GENERIC_MSG);
    }
  }

  /**
   * Check to make sure VM size (type) is supported.
   *
   * @param directorConfig      Director config
   * @param accumulator         error accumulator
   * @param localizationContext localization context to extract config
   */
  void checkVMSize(Configured directorConfig, PluginExceptionConditionAccumulator accumulator,
    LocalizationContext localizationContext) {
    String vmSize = directorConfig.getConfigurationValue(
      AzureComputeInstanceTemplateConfigurationProperty.VMSIZE, localizationContext);

    if (!pluginConfig.getStringList(AZURE_CONFIG_INSTANCE_SUPPORTED).contains(vmSize)) {
      LOG.error(String.format(VIRTUAL_MACHINE_MSG, vmSize));
      addError(accumulator, AzureComputeInstanceTemplateConfigurationProperty.VMSIZE,
        localizationContext, null, VIRTUAL_MACHINE_MSG, vmSize);
    }
  }

  /**
   * Check that the Resource Group exists in Azure.
   * For certain fields - e.g. Virtual Network, Network Security Group - the Resource Group
   * is specified separately.
   * <p>
   * Azure will throw an exception if it can't find the Resource Group.
   *
   * @param directorConfig      Director config
   * @param accumulator         error accumulator
   * @param localizationContext localization context to extract config
   */
  void checkResourceGroup(Configured directorConfig,
    PluginExceptionConditionAccumulator accumulator, LocalizationContext localizationContext,
    AzureComputeProviderHelper helper) {
    String rgName = directorConfig.getConfigurationValue(
      AzureComputeInstanceTemplateConfigurationProperty.COMPUTE_RESOURCE_GROUP, localizationContext);

    try {
      helper.getResourceGroup(rgName);
    } catch (ServiceException | IOException | URISyntaxException e) {
      String message;
      if (e instanceof IOException) {
        message = COMMUNICATION_MSG;
      } else {
        message = RESOURCE_GROUP_MSG;
      }
      LOG.error(String.format(message, rgName));
      addError(accumulator, AzureComputeInstanceTemplateConfigurationProperty.COMPUTE_RESOURCE_GROUP,
        localizationContext, null, message, rgName);
    }
  }

  /**
   * Check that the Virtual Network Resource Group exists in Azure.
   * This Resource Group defines where to look for the Virtual Network.
   * <p>
   * Azure will throw an exception if it can't find the Resource Group.
   *
   * @param directorConfig      Director config
   * @param accumulator         error accumulator
   * @param localizationContext localization context to extract config
   */
  void checkVirtualNetworkResourceGroup(Configured directorConfig,
    PluginExceptionConditionAccumulator accumulator, LocalizationContext localizationContext,
    AzureComputeProviderHelper helper) {
    String vnrgName = directorConfig.getConfigurationValue(
      AzureComputeInstanceTemplateConfigurationProperty.VIRTUAL_NETWORK_RESOURCE_GROUP,
      localizationContext);

    try {
      helper.getResourceGroup(vnrgName);
    } catch (IOException | ServiceException | URISyntaxException e) {
      String message;
      if (e instanceof IOException) {
        message = COMMUNICATION_MSG;
      } else {
        message = VIRTUAL_NETWORK_RESOURCE_GROUP_MSG;
      }
      LOG.error(String.format(message, vnrgName));
      addError(accumulator,
        AzureComputeInstanceTemplateConfigurationProperty.VIRTUAL_NETWORK_RESOURCE_GROUP,
        localizationContext, null, message, vnrgName);
    }
  }

  /**
   * Check that the Virtual Network exists in Azure.
   * <p>
   * Azure will throw an exception if it can't find the Virtual Network.
   *
   * @param directorConfig      Director config
   * @param accumulator         error accumulator
   * @param localizationContext localization context to extract config
   */
  void checkVirtualNetwork(Configured directorConfig,
    PluginExceptionConditionAccumulator accumulator, LocalizationContext localizationContext,
    AzureComputeProviderHelper helper) {
    String vnName = directorConfig.getConfigurationValue(
      AzureComputeInstanceTemplateConfigurationProperty.VIRTUAL_NETWORK, localizationContext);
    String vnrgName = directorConfig.getConfigurationValue(
      AzureComputeInstanceTemplateConfigurationProperty.VIRTUAL_NETWORK_RESOURCE_GROUP,
      localizationContext);

    try {
      helper.getVirtualNetworkByName(vnrgName, vnName);
    } catch (IOException | ServiceException e) {
      String message;
      if (e instanceof IOException) {
        message = COMMUNICATION_MSG;
      } else {
        message = VIRTUAL_NETWORK_MSG;
      }
      LOG.error(String.format(message, vnName, vnrgName));
      addError(accumulator, AzureComputeInstanceTemplateConfigurationProperty.VIRTUAL_NETWORK,
        localizationContext, null, message, vnName, vnrgName);
    }
  }

  /**
   * Check that the subnet exists under the Virtual Network in Azure.
   * <p>
   * Azure will throw an exception if it can't find the subnet.
   *
   * @param directorConfig      Director config
   * @param accumulator         error accumulator
   * @param localizationContext localization context to extract config
   */
  void checkSubnet(Configured directorConfig, PluginExceptionConditionAccumulator accumulator,
    LocalizationContext localizationContext, AzureComputeProviderHelper helper) {
    String vnName = directorConfig.getConfigurationValue(
      AzureComputeInstanceTemplateConfigurationProperty.VIRTUAL_NETWORK, localizationContext);
    String vnrgName = directorConfig.getConfigurationValue(
      AzureComputeInstanceTemplateConfigurationProperty.VIRTUAL_NETWORK_RESOURCE_GROUP,
      localizationContext);
    String subnetName = directorConfig.getConfigurationValue(
      AzureComputeInstanceTemplateConfigurationProperty.SUBNET_NAME,
      localizationContext);

    try {
      helper.getSubnetByName(vnrgName, vnName, subnetName);
    } catch (IOException | ServiceException e) {
      String message;
      if (e instanceof IOException) {
        message = COMMUNICATION_MSG;
      } else {
        message = SUBNET_MSG;
      }
      LOG.error(String.format(message, subnetName, vnrgName));
      addError(accumulator, AzureComputeInstanceTemplateConfigurationProperty.SUBNET_NAME,
        localizationContext, null, message, subnetName, vnName);
    }
  }

  /**
   * Check to make sure FQDN suffix satisfies Azure DNS label and hostname requirements.
   *
   * @param directorConfig      Director config
   * @param accumulator         error accumulator
   * @param localizationContext localization context to extract config
   */
  void checkFQDNSuffix(Configured directorConfig, PluginExceptionConditionAccumulator accumulator,
    LocalizationContext localizationContext) {
    String suffix = directorConfig.getConfigurationValue(
      AzureComputeInstanceTemplateConfigurationProperty.HOST_FQDN_SUFFIX, localizationContext);
    String regex = pluginConfig.getString(AZURE_CONFIG_INSTANCE_FQDN_SUFFIX_REGEX);
    Pattern pattern = Pattern.compile(regex);
    Matcher matcher = pattern.matcher(suffix);

    if (!matcher.find()) {
      LOG.error(String.format(FQDN_SUFFIX_MSG, suffix, regex));
      addError(accumulator, AzureComputeInstanceTemplateConfigurationProperty.HOST_FQDN_SUFFIX,
        localizationContext, null, FQDN_SUFFIX_MSG, suffix, regex);
    }
  }

  /**
   * Check that the Resource Group exists in Azure.
   * This Resource Group defines where to look for the Network Security Group.
   * <p>
   * Azure will throw an exception if it can't find the Resource Group.
   *
   * @param directorConfig      Director config
   * @param accumulator         error accumulator
   * @param localizationContext localization context to extract config
   */
  void checkNetworkSecurityGroupResourceGroup(Configured directorConfig,
    PluginExceptionConditionAccumulator accumulator, LocalizationContext localizationContext,
    AzureComputeProviderHelper helper) {
    String nsgrgName = directorConfig.getConfigurationValue(
      AzureComputeInstanceTemplateConfigurationProperty.NETWORK_SECURITY_GROUP_RESOURCE_GROUP,
      localizationContext);

    try {
      helper.getResourceGroup(nsgrgName);
    } catch (IOException | ServiceException | URISyntaxException e) {
      String message;
      if (e instanceof IOException) {
        message = COMMUNICATION_MSG;
      } else {
        message = NETWORK_SECURITY_GROUP_RESOURCE_GROUP_MSG;
      }
      LOG.error(String.format(message, nsgrgName));
      addError(accumulator,
        AzureComputeInstanceTemplateConfigurationProperty.NETWORK_SECURITY_GROUP_RESOURCE_GROUP,
        localizationContext, null, message, nsgrgName);
    }
  }

  /**
   * Check that the Network Security Group exists within the Resource Group.
   * <p>
   * Azure will throw an exception if it can't find the Network Security Group.
   *
   * @param directorConfig      Director config
   * @param accumulator         error accumulator
   * @param localizationContext localization context to extract config
   */
  void checkNetworkSecurityGroup(Configured directorConfig,
    PluginExceptionConditionAccumulator accumulator, LocalizationContext localizationContext,
    AzureComputeProviderHelper helper) {
    String nsgName = directorConfig.getConfigurationValue(
      AzureComputeInstanceTemplateConfigurationProperty.NETWORK_SECURITY_GROUP,
      localizationContext);
    String nsgrgName = directorConfig.getConfigurationValue(
      AzureComputeInstanceTemplateConfigurationProperty.NETWORK_SECURITY_GROUP_RESOURCE_GROUP,
      localizationContext);

    try {
      helper.getNetworkSecurityGroupByName(nsgrgName, nsgName);
    } catch (IOException | ServiceException e) {
      String message;
      if (e instanceof IOException) {
        message = COMMUNICATION_MSG;
      } else {
        message = NETWORK_SECURITY_GROUP_MSG;
      }
      LOG.error(String.format(message, nsgName, nsgrgName));
      addError(accumulator,
        AzureComputeInstanceTemplateConfigurationProperty.NETWORK_SECURITY_GROUP,
        localizationContext, null, message, nsgName, nsgrgName);
    }
  }

  /**
   * Check that the Availability Set exists in Azure.
   * <p>
   * Azure will throw an exception if it can't find the Availability Set.
   *
   * @param directorConfig      Director config
   * @param accumulator         error accumulator
   * @param localizationContext localization context to extract config
   */
  void checkAvailabilitySet(Configured directorConfig,
    PluginExceptionConditionAccumulator accumulator, LocalizationContext localizationContext,
    AzureComputeProviderHelper helper) {
    String asName = directorConfig.getConfigurationValue(
      AzureComputeInstanceTemplateConfigurationProperty.AVAILABILITY_SET, localizationContext);
    String computeRgName = directorConfig.getConfigurationValue(
      AzureComputeInstanceTemplateConfigurationProperty.COMPUTE_RESOURCE_GROUP, localizationContext);

    try {
      helper.getAvailabilitySetByName(computeRgName, asName);
    } catch (IOException | ServiceException | URISyntaxException e) {
      String message;
      if (e instanceof IOException) {
        message = COMMUNICATION_MSG;
      } else {
        message = AVAILABILITY_SET_MSG;
      }
      LOG.error(String.format(message, asName));
      addError(accumulator,
        AzureComputeInstanceTemplateConfigurationProperty.AVAILABILITY_SET,
        localizationContext, null, message, asName);
    }
  }

  /**
   * Check to make sure instance name prefix satisfies Azure DNS label requirements.
   *
   * @param directorConfig      Director config
   * @param accumulator         error accumulator
   * @param localizationContext localization context to extract config
   */
  void checkInstancePrefix(Configured directorConfig,
    PluginExceptionConditionAccumulator accumulator, LocalizationContext localizationContext) {
    String instancePrefix = directorConfig.getConfigurationValue(
      InstanceTemplate.InstanceTemplateConfigurationPropertyToken.INSTANCE_NAME_PREFIX,
      localizationContext);
    String regex = pluginConfig.getString(AZURE_CONFIG_INSTANCE_DNS_LABEL_REGEX);
    Pattern pattern = Pattern.compile(regex);
    Matcher matcher = pattern.matcher(instancePrefix);

    if (!matcher.find()) {
      LOG.error(String.format(INSTANCE_NAME_PREFIX_MSG, instancePrefix, regex));
      addError(accumulator,
        InstanceTemplate.InstanceTemplateConfigurationPropertyToken.INSTANCE_NAME_PREFIX,
        localizationContext, null, INSTANCE_NAME_PREFIX_MSG, instancePrefix, regex);
    }
  }

  /**
   * Check if VM image is specified in the configurable images file. If so then verify if the image
   * exists in Azure Marketplace.
   *
   * @param directorConfig      Director config
   * @param accumulator         error accumulator
   * @param localizationContext localization context to extract config
   */
  void checkVmImage(Configured directorConfig, PluginExceptionConditionAccumulator accumulator,
    LocalizationContext localizationContext,
    AzureComputeProviderHelper helper) {
    String imageName = directorConfig.getConfigurationValue(IMAGE, localizationContext);
    Config imageCfg;
    try {
      imageCfg = configurableImages.getConfig(imageName);
    } catch (ConfigException.Missing | ConfigException.WrongType e) {
      LOG.error(String.format(IMAGE_MISSING_IN_CONFIG_MSG, imageName));
      addError(accumulator, IMAGE, localizationContext, null, IMAGE_MISSING_IN_CONFIG_MSG,
        imageName);
      return;
    }

    String publisher;
    String sku;
    String offer;
    String version;

    try {
      publisher = imageCfg.getString(Configurations.AZURE_IMAGE_PUBLISHER);
      sku = imageCfg.getString(Configurations.AZURE_IMAGE_SKU);
      offer = imageCfg.getString(Configurations.AZURE_IMAGE_OFFER);
      version = imageCfg.getString(Configurations.AZURE_IMAGE_VERSION);
    } catch (ConfigException.Missing | ConfigException.WrongType e) {
      LOG.error(String.format(IMAGE_CFG_MISSING_REQUIRED_FIELD_MSG, imageName));
      addError(accumulator, IMAGE, localizationContext, null, IMAGE_CFG_MISSING_REQUIRED_FIELD_MSG,
        imageName);
      return;
    }

    AzureVmImageInfo imageInfo = new AzureVmImageInfo(publisher, sku, offer, version);
    try {
      /*
       * check if the image is available in the data center specified in the location.
       * If service principle doesn't have at least read permission at the subscription level,
       * an IllegalArgumentException may be thrown by the SDK.
       */
      helper.getMarketplaceVMImage(location, imageInfo);
    } catch (ServiceException | IOException | URISyntaxException |
      IllegalArgumentException e) {
      String message;
      if (e instanceof IOException) {
        message = COMMUNICATION_MSG;
      } else if (e instanceof IllegalArgumentException) {
        message = ILLEGAL_ARGUMENT_EXCEPTION_MSG;
      } else {
        message = IMAGE_MISSING_IN_AZURE_MSG;
      }
      LOG.error(String.format(message, imageInfo.toString()));
      addError(accumulator, IMAGE, localizationContext, null, message, imageInfo);
    }
  }
}
