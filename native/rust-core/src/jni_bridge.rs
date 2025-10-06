use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

#[no_mangle]
pub extern "C" fn Java_expo_modules_rustbridge_ExpoRustBridgeModule_nativeLogFromRust(
    mut env: JNIEnv,
    _class: JClass,
    message: JString,
) -> jstring {
    let input: String = env
        .get_string(&message)
        .expect("Couldn't get java string!")
        .into();

    let result = crate::log_from_rust(input);

    let output = env
        .new_string(result)
        .expect("Couldn't create java string!");

    output.into_raw()
}
