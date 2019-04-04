package org.myrobotlab.encoder;

import java.util.HashSet;
import java.util.Set;

import org.myrobotlab.framework.interfaces.Attachable;
import org.myrobotlab.sensor.EncoderData;
import org.myrobotlab.service.interfaces.EncoderControl;
import org.myrobotlab.service.interfaces.EncoderController;
import org.myrobotlab.service.Runtime;
import org.myrobotlab.service.interfaces.EncoderListener;

public class GenericEncoder implements EncoderControl {
  
  transient EncoderController controller;
  transient Set<EncoderListener> listeners = new HashSet<>();
  String pin;

  @Override
  public void attach(Attachable attachable) throws Exception {
    if (EncoderController.class.isAssignableFrom(attachable.getClass())) {
      controller = (EncoderController)controller;
      controller.attach(this);
    }
    if (EncoderListener.class.isAssignableFrom(attachable.getClass())) {
      EncoderListener listener = (EncoderListener)attachable;
      listeners.add(listener);
      listener.attach(this);
    }
  }

  @Override
  public void attach(String serviceName) throws Exception {
    attach(Runtime.getService(serviceName));
  }

  @Override
  public void detach(Attachable attachable) {
    if (attachable == null) {
      return;
    }
    if (EncoderController.class.isAssignableFrom(attachable.getClass())) {
      controller = null;
      controller.detach(this);
    }
    if (EncoderListener.class.isAssignableFrom(attachable.getClass())) {
      EncoderListener listener = (EncoderListener)attachable;
      listeners.remove(listener);
      listener.detach(this);
    }
  }

  @Override
  public void detach(String serviceName) {
    detach(Runtime.getService(serviceName));
  }

  @Override
  public void detach() {
      detach(this);      
  }

  @Override
  public Set<String> getAttached() {
    Set<String> ret = new HashSet<String>();
    for (EncoderListener listener : listeners) {
      ret.add(listener.getName());
    }
    if (controller != null) {
      ret.add(controller.getName());
    }
    return ret;
  }

  @Override
  public boolean isAttached(Attachable attachable) {
    if (attachable == null) {
      return false;
    }
    if (EncoderController.class.isAssignableFrom(attachable.getClass())) {
      return controller == attachable;
    }
    if (EncoderListener.class.isAssignableFrom(attachable.getClass())) {
      EncoderListener listener = (EncoderListener)attachable;
      return listeners.contains(listener);      
    }
    return false;
  }

  @Override
  public boolean isAttached(String name) {
    return isAttached(Runtime.getService(name));
  }

  @Override
  public boolean isLocal() {
    return true;
  }

  @Override
  public String getName() {
    if (controller != null) {
      return controller.getName();
    }
    return null;
  }

  @Override
  public void attach(EncoderController controller, Integer pin) throws Exception {
    setPin(pin);
    attach(controller);
  }

  @Override
  public void setController(EncoderController controller) {
    this.controller = controller;
  }

  @Override
  public void onEncoderData(EncoderData data) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public void setPin(String pin) {
    this.pin = pin;
  }

  @Override
  public void setPin(int address) {
    // TODO Auto-generated method stub
    
  }

  @Override
  public String getPin() {
    // TODO Auto-generated method stub
    return null;
  }

}